package mycode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic（Claude）原生 Messages API 客户端。
 *
 * 它跟 OpenAI 协议有 6 处硬性差异，一处处理错就直接 400，全部在这里转换掉：
 *
 * | 项目        | OpenAI 兼容                    | Anthropic                              |
 * |------------|-------------------------------|----------------------------------------|
 * | 端点        | /v1/chat/completions          | /v1/messages                           |
 * | 鉴权        | Authorization: Bearer sk-xxx  | x-api-key: sk-ant-xxx + anthropic-version |
 * | system     | 一条 role=system 的消息        | 顶层 system 字段，不在 messages 里       |
 * | 工具声明    | {type:function, function:{}}  | {name, description, input_schema}      |
 * | 工具回填    | role:"tool" + tool_call_id    | role:"user" + content[{type:tool_result}] |
 * | max_tokens | 可选                          | 必填                                    |
 *
 * 另有一条隐形规则最坑：messages 必须 user/assistant 交替，
 * 模型一次调 3 个工具会产生 3 条结果，必须合并进同一条 user 消息，否则报错。
 * @author my
 */
public class AnthropicClient implements LlmClient {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String API_VERSION = "2023-06-01";

    /** Claude 的密钥（sk-ant- 开头），走 x-api-key 头，不是 Bearer */
    private final String apiKey;

    /** 模型名，例如 claude-sonnet-4-5 */
    private final String model;

    /** 完整请求地址（/v1/messages），不是 baseUrl */
    private final String endpoint;

    /** 最大输出 token 数。Anthropic 强制要求，缺了直接 400，所以给个默认值 4096 */
    private final int maxTokens;

    /** 复用 HttpClient，避免每次调用重建连接池 */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();

    /**
     * 最常用构造：使用官方地址，max_tokens 默认 4096。
     *
     * @param apiKey 密钥
     * @param model  模型名
     */
    public AnthropicClient(String apiKey, String model) {
        this(apiKey, model, "https://api.anthropic.com/v1/messages", 4096);
    }

    /**
     * 完整构造，用于走中转代理或调整输出长度上限。
     *
     * @param apiKey    密钥
     * @param model     模型名
     * @param endpoint  完整请求地址
     * @param maxTokens 最大输出 token 数
     */
    public AnthropicClient(String apiKey, String model, String endpoint, int maxTokens) {
        this.apiKey = apiKey;
        this.model = model;
        this.endpoint = endpoint;
        this.maxTokens = maxTokens;
    }

    /** 单独暴露，方便用 --dump 和 OpenAI 报文逐字段对比 */
    public String buildRequestJson(AgentContext ctx, List<Tool> tools) throws Exception {
        StringBuilder system = new StringBuilder();
        List<Map<String, Object>> messages = toAnthropicMessages(ctx.messages(), system);

        ObjectNode body = M.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);          // 必填，少写就是 400
        if (system.length() > 0) {
            body.put("system", system.toString());  // 顶层字段，不进 messages
        }
        body.set("messages", M.valueToTree(messages));

        if (!tools.isEmpty()) {
            ArrayNode arr = body.putArray("tools");
            for (Tool t : tools) {
                ObjectNode fn = M.createObjectNode();
                fn.put("name", t.name());
                fn.put("description", t.description());
                // schema 排序保证跨重启字节恒定，与 OpenAiCompatibleClient 同一套规则
                fn.set("input_schema", M.valueToTree(Tool.canonical(t.parameters())));  // 注意：不是 parameters
                arr.add(fn);
            }
            ObjectNode choice = M.createObjectNode();
            choice.put("type", "auto");
            body.set("tool_choice", choice);           // 注意：是对象不是字符串
        }
        return M.writeValueAsString(body);
    }

    /**
     * 发起一轮对话。
     *
     * 鉴权不用 Bearer，而是两个专用头：
     *   x-api-key        —— Claude 的密钥
     *   anthropic-version —— 协议版本，写死 2023-06-01，不写会 400
     */
    @Override
    public ChatResult chat(AgentContext ctx, List<Tool> tools) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestJson(ctx, tools)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Anthropic HTTP " + resp.statusCode() + " -> "
                    + resp.body().substring(0, Math.min(400, resp.body().length())));
        }
        return parse(M.readTree(resp.body()));
    }

    /**
     * 解析响应报文。
     * 结构：content 是一个块数组，可能混着 text 块和 tool_use 块，
     * 所以不能用「取第一个字段」的思路，必须逐个块遍历。
     */
    private ChatResult parse(JsonNode root) throws Exception {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();

        for (JsonNode block : root.path("content")) {
            String type = block.path("type").asText();
            if ("text".equals(type)) {
                text.append(block.path("text").asText());
            } else if ("tool_use".equals(type)) {
                String inputJson = M.writeValueAsString(block.path("input"));
                calls.add(new ToolCall(block.path("id").asText(), block.path("name").asText(),
                        inputJson, OpenAiCompatibleClient.Args.parse(M, inputJson)));
            }
        }
        // stop_reason = tool_use 时表示要执行工具，end_turn 表示说完了。
        // 用量字段名和 OpenAI 不同：input_tokens / output_tokens / cache_read_input_tokens
        JsonNode u = root.path("usage");
        Usage usage = new Usage(u.path("input_tokens").asInt(0),
                u.path("output_tokens").asInt(0), u.path("cache_read_input_tokens").asInt(0));
        return new ChatResult(text.length() == 0 ? null : text.toString(), calls, usage);
    }

    // ---------------- 协议转换核心 ----------------

    private List<Map<String, Object>> toAnthropicMessages(List<Map<String, Object>> source,
                                                          StringBuilder system) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();

        for (Map<String, Object> m : source) {
            String role = String.valueOf(m.get("role"));

            if ("system".equals(role)) {
                Object c = m.get("content");
                if (c != null && !String.valueOf(c).isBlank()) {
                    if (system.length() > 0) system.append("\n\n");
                    system.append(c);
                }
                continue;   // system 不进 messages
            }

            String outRole;
            List<Map<String, Object>> blocks = new ArrayList<>();

            if ("assistant".equals(role)) {
                outRole = "assistant";
                String txt = m.get("content") == null ? "" : String.valueOf(m.get("content"));
                if (!txt.isBlank()) blocks.add(textBlock(txt));
                if (m.get("tool_calls") instanceof List<?> list) {
                    for (Object o : list) {
                        Map<?, ?> call = (Map<?, ?>) o;
                        Map<?, ?> fn = (Map<?, ?>) call.get("function");
                        Map<String, Object> block = new LinkedHashMap<>();
                        block.put("type", "tool_use");
                        block.put("id", call.get("id"));
                        block.put("name", fn.get("name"));
                        block.put("input", parseInput(String.valueOf(fn.get("arguments"))));
                        blocks.add(block);
                    }
                }
            } else if ("tool".equals(role)) {
                // 工具结果在 Anthropic 里是 user 消息里的 tool_result 块
                outRole = "user";
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "tool_result");
                block.put("tool_use_id", m.get("tool_call_id"));
                block.put("content", m.get("content") == null ? "" : String.valueOf(m.get("content")));
                blocks.add(block);
            } else {
                outRole = "user";
                // content 可能是纯文本，也可能是带图片的块数组（--image 存的 OpenAI 形态）
                appendUserBlocks(m.get("content"), blocks);
            }

            if (blocks.isEmpty()) continue;   // Anthropic 不接受空 content

            // 相邻同角色必须合并，尤其是并行工具结果
            Map<String, Object> prev = out.isEmpty() ? null : out.get(out.size() - 1);
            if (prev != null && outRole.equals(prev.get("role"))) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> prevBlocks = (List<Map<String, Object>>) prev.get("content");
                prevBlocks.addAll(blocks);
            } else {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", outRole);
                msg.put("content", blocks);
                out.add(msg);
            }
        }
        return out;
    }

    /** 构造一个 text 文本块。Anthropic 的 content 是块数组，纯文本也必须包成块 */
    private Map<String, Object> textBlock(String text) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "text");
        b.put("text", text);
        return b;
    }

    /**
     * user 消息的 content 转成 Anthropic 块。
     * 纯文本 → 一个 text 块；块数组（OpenAI 形态）逐个转换：
     * text → text 块，image_url 的 data URL → base64 图片块。
     */
    private void appendUserBlocks(Object content, List<Map<String, Object>> blocks) {
        if (content instanceof List<?> parts) {
            for (Object o : parts) {
                if (!(o instanceof Map<?, ?> part)) continue;
                if ("text".equals(part.get("type"))) {
                    blocks.add(textBlock(String.valueOf(part.get("text"))));
                } else if ("image_url".equals(part.get("type"))
                        && part.get("image_url") instanceof Map<?, ?> urlObj) {
                    addImageBlock(String.valueOf(urlObj.get("url")), blocks);
                }
            }
            return;
        }
        blocks.add(textBlock(content == null ? "" : String.valueOf(content)));
    }

    /** 把 data:image/png;base64,xxxx 形态的 URL 转成 Anthropic 的图片块；非 base64 的 URL 跳过 */
    private void addImageBlock(String dataUrl, List<Map<String, Object>> blocks) {
        final String prefix = "data:";
        final String marker = ";base64,";
        if (dataUrl == null || !dataUrl.startsWith(prefix) || !dataUrl.contains(marker)) {
            return;
        }
        int comma = dataUrl.indexOf(marker);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "base64");
        source.put("media_type", dataUrl.substring(prefix.length(), comma));
        source.put("data", dataUrl.substring(comma + marker.length()));
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "image");
        block.put("source", source);
        blocks.add(block);
    }

    /** tool_use.input 必须是 JSON 对象，不能是字符串 */
    private Object parseInput(String argsJson) {
        try {
            JsonNode node = M.readTree(argsJson);
            if (node.isObject()) {
                return M.convertValue(node, Map.class);
            }
        } catch (Exception ignore) {
        }
        return new LinkedHashMap<String, Object>();
    }
}
