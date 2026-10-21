package miniagent;

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
 * 通用 OpenAI 兼容客户端。
 *
 * 全世界绝大多数模型服务都实现了 /v1/chat/completions 这套协议：
 * OpenAI、DeepSeek、Kimi、智谱、通义（DashScope 兼容模式）、本地 Ollama / vLLM。
 * 所以只要把 endpoint 参数化，一份代码就能接完，这就是「兼容层」的价值。
 *
 * 报文结构要点：
 *   工具声明   tools -> [{type:"function", function:{name, description, parameters}}]
 *   工具调用   assistant 消息里的 tool_calls 数组，每项带 id
 *   结果回填   role:"tool" + tool_call_id
 */
public class OpenAiCompatibleClient implements LlmClient {

    /** JSON 工具。线程安全，做成静态常量供所有实例复用 */
    protected static final ObjectMapper M = new ObjectMapper();

    /** 完整的请求地址（不是 baseUrl），例如 https://api.openai.com/v1/chat/completions */
    protected final String endpoint;

    /** API Key。本地 Ollama 这类服务可以传空串 */
    protected final String apiKey;

    /** 模型名，例如 gpt-4.1、deepseek-chat、qwen3-max */
    protected final String model;

    /** 额外请求头。用于某些需要自定义头的代理或企业网关 */
    protected final Map<String, String> extraHeaders;

    /** 复用 HttpClient，避免每次调用都重建连接池 */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();

    /** 最简构造：不需要额外请求头时用这个 */
    public OpenAiCompatibleClient(String endpoint, String apiKey, String model) {
        this(endpoint, apiKey, model, Map.of());
    }

    /**
     * 完整构造。
     *
     * @param endpoint     完整请求地址
     * @param apiKey       密钥，可为空（本地服务）
     * @param model        模型名
     * @param extraHeaders 额外请求头，没有就传 Map.of()
     */
    public OpenAiCompatibleClient(String endpoint, String apiKey, String model,
                                  Map<String, String> extraHeaders) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.extraHeaders = extraHeaders;
    }

    /**
     * 组装请求报文。
     *
     * 单独抽成 public 方法有两个好处：
     *   1. --dump 模式下可以只打印报文、不发请求，白嫖协议调试；
     *   2. 出问题时可以直接把这段 JSON 粘到 Postman 里复现。
     */
    public String buildRequestJson(AgentContext ctx, List<Tool> tools) throws Exception {
        ObjectNode body = M.createObjectNode();
        body.put("model", model);
        // 上下文消息本身就按 OpenAI 协议存的，直接序列化即可，不用转换
        body.set("messages", M.valueToTree(ctx.messages()));

        if (!tools.isEmpty()) {
            ArrayNode arr = body.putArray("tools");
            for (Tool t : tools) {
                // 注意 OpenAI 的结构比 Anthropic 多包了一层 function
                ObjectNode fn = M.createObjectNode();
                fn.put("name", t.name());
                fn.put("description", t.description());
                fn.set("parameters", M.valueToTree(t.parameters()));
                ObjectNode wrapper = M.createObjectNode();
                wrapper.put("type", "function");
                wrapper.set("function", fn);
                arr.add(wrapper);
            }
            // auto 表示由模型自己决定调不调工具；传 none 则禁用，传 required 则强制调
            body.put("tool_choice", "auto");
        }
        // qwen3 系列默认开思考模式，配合工具调用会明显变慢，演示时先关掉
        if (model.startsWith("qwen3")) {
            body.put("enable_thinking", false);
        }
        return M.writeValueAsString(body);
    }

    /**
     * 发起一轮对话。
     *
     * 超时给到 120 秒：推理型模型 + 长上下文时，响应超过 60 秒很常见，
     * 超时设太短会出现「请求其实成功了，但本地已经断开」的情况。
     */
    @Override
    public ChatResult chat(AgentContext ctx, List<Tool> tools) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json");
        // 本地服务（如 Ollama）不需要鉴权，空 key 就不发这个头
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        extraHeaders.forEach(builder::header);

        HttpRequest req = builder
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestJson(ctx, tools)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            // 带上响应体前 300 字，401/400 时能直接看出是 key 错了还是参数不对
            throw new IllegalStateException("HTTP " + resp.statusCode() + " -> "
                    + resp.body().substring(0, Math.min(300, resp.body().length())));
        }
        return parse(M.readTree(resp.body()));
    }

    /**
     * 解析响应报文。
     * 结构：choices[0].message.content 是文本，.tool_calls[] 是工具调用列表。
     */
    protected ChatResult parse(JsonNode root) throws Exception {
        JsonNode msg = root.path("choices").path(0).path("message");
        String text = msg.path("content").isMissingNode() || msg.path("content").isNull()
                ? null : msg.path("content").asText();

        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode c : msg.path("tool_calls")) {
            // arguments 是「字符串形式的 JSON」，不是对象，这一点经常被搞错
            String argsJson = c.path("function").path("arguments").asText("{}");
            calls.add(new ToolCall(c.path("id").asText(),
                    c.path("function").path("name").asText(),
                    argsJson, Args.parse(M, argsJson)));
        }
        return new ChatResult(text, calls);
    }

    /**
     * 参数解析工具。
     * 模型偶尔会吐出不合法 JSON（比如字符串里带未转义的引号），
     * 这里吞掉异常返回空 Map，让工具自己报「缺参数」，而不是让整个循环崩掉。
     */
    static class Args {

        /**
         * 把 JSON 字符串转成 Map。
         *
         * @param m    ObjectMapper
         * @param json 模型返回的参数字符串
         * @return 解析结果；解析失败时返回空 Map（不抛异常）
         */
        static Map<String, Object> parse(ObjectMapper m, String json) {
            Map<String, Object> out = new LinkedHashMap<>();
            try {
                m.readTree(json).fields().forEachRemaining(e ->
                        out.put(e.getKey(), m.convertValue(e.getValue(), Object.class)));
            } catch (Exception ignore) {
                // 故意忽略：交给工具层的参数校验去报错，信息更准确
            }
            return out;
        }
    }
}
