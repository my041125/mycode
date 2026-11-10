package mycode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * SSE 增量累加器：把一串 data 片段拼成一个完整的 ChatResult。
 *
 * 流式响应比一次性响应麻烦得多，三件事都得自己处理：
 *   1. 文本是分片的，直接拼起来就行；
 *   2. tool_calls 更是分片的 —— 第一片只有 id 和函数名，参数是后续一片片追加的，
 *      所以必须按 index 分组，把 arguments 逐片拼成一个完整 JSON 字符串；
 *   3. usage 只在最后一个 chunk 里出现，前面的 chunk 都没有。
 *
 * 把这段逻辑单独抽出来，是为了能脱离网络做单元测试：
 * 喂几段假 JSON 进去，断言拼出来的结果对不对。
 * @author my
 */
public class SseAccumulator {

    private static final ObjectMapper M = new ObjectMapper();

    /** 已拼好的文本 */
    private final StringBuilder text = new StringBuilder();

    /** 按 index 分组的工具调用片段。LinkedHashMap 保证顺序与下发顺序一致 */
    private final Map<Integer, Partial> partials = new LinkedHashMap<>();

    /** 文本增量回调，可为 null（不需要流式展示时） */
    private final Consumer<String> onText;

    /** 最后拿到的用量 */
    private Usage usage = Usage.ZERO;

    /**
     * 一次工具调用的未完成状态。
     * id 和 name 只在第一片里有，arguments 则可能分散在任意多片里。
     */
    private static class Partial {
        String id = "";
        String name = "";
        final StringBuilder args = new StringBuilder();
    }

    /**
     * @param onText 收到文本增量时的回调，传 null 表示只关心最终结果
     */
    public SseAccumulator(Consumer<String> onText) {
        this.onText = onText;
    }

    /**
     * 吃进一条 data 负载（不含 "data:" 前缀）。
     *
     * @param dataJson 一条完整的 JSON 字符串
     */
    public void accept(String dataJson) {
        if (dataJson == null || dataJson.isBlank()) return;
        JsonNode root;
        try {
            root = M.readTree(dataJson);
        } catch (Exception e) {
            return;   // 半个 JSON 或心跳包，跳过
        }

        // 1. 用量：通常只在最后一个 chunk 出现
        JsonNode u = root.path("usage");
        if (u.isObject()) {
            // 缓存命中数也要从流里捞出来：DeepSeek 的流式 usage 同样带 prompt_cache_hit_tokens
            usage = new Usage(u.path("prompt_tokens").asInt(0),
                    u.path("completion_tokens").asInt(0), OpenAiCompatibleClient.cachedTokens(u));
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) return;
        JsonNode delta = choices.get(0).path("delta");

        // 2. 文本增量
        JsonNode content = delta.path("content");
        if (content.isTextual() && !content.asText().isEmpty()) {
            String piece = content.asText();
            text.append(piece);
            if (onText != null) onText.accept(piece);
        }

        // 3. 工具调用增量：按 index 分组累积
        for (JsonNode tc : delta.path("tool_calls")) {
            int index = tc.path("index").asInt(0);
            Partial p = partials.computeIfAbsent(index, k -> new Partial());
            if (tc.hasNonNull("id")) {
                p.id = tc.get("id").asText();
            }
            JsonNode fn = tc.path("function");
            if (fn.hasNonNull("name")) {
                p.name = fn.get("name").asText();
            }
            if (fn.hasNonNull("arguments")) {
                p.args.append(fn.get("arguments").asText());
            }
        }
    }

    /** 已拼好的完整文本 */
    public String text() {
        return text.length() == 0 ? null : text.toString();
    }

    /** 已拼好的工具调用列表。arguments 为空时给个合法空对象，避免下游解析炸掉 */
    public List<ToolCall> toolCalls() {
        List<ToolCall> out = new ArrayList<>();
        for (Partial p : partials.values()) {
            if (p.name == null || p.name.isBlank()) continue;
            String argsJson = p.args.length() == 0 ? "{}" : p.args.toString();
            out.add(new ToolCall(p.id, p.name, argsJson,
                    OpenAiCompatibleClient.Args.parse(M, argsJson)));
        }
        return out;
    }

    /** 用量，没收到就是 0 */
    public Usage usage() {
        return usage;
    }
}
