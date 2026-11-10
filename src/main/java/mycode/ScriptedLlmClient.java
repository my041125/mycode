package mycode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 脚本化假模型：按预设顺序返回工具调用，脚本走完就输出最终答案。
 *
 * 用途是「先验证控制流，再接真模型」。调 agent 时 80% 的 bug 出在循环、
 * 上下文拼接、工具分发上，跟模型本身没关系。用假模型把这些跑对，剩下就好办了。
 *
 * 典型用法：
 *   ScriptedLlmClient.of(
 *       List.of(call("call_1", "read_code", Map.of("path", "A.java")),
 *               call("call_2", "edit_code", Map.of("op", "replace", ...))),
 *       ctx -> "改好了")
 * @author my
 */
public class ScriptedLlmClient implements LlmClient {

    /** 预设的工具调用序列，按顺序依次吐出 */
    private final List<ToolCall> script;

    /** 脚本走完后用来生成最终回答的函数，入参是当前上下文，可以读取工具结果 */
    private final Function<AgentContext, String> answer;

    /** 脚本游标，记录下一条该吐哪个调用 */
    private int cursor = 0;

    private ScriptedLlmClient(List<ToolCall> script, Function<AgentContext, String> answer) {
        this.script = script;
        this.answer = answer;
    }

    /** 工厂方法：脚本 + 最终回答生成函数 */
    public static ScriptedLlmClient of(List<ToolCall> script, Function<AgentContext, String> answer) {
        return new ScriptedLlmClient(script, answer);
    }

    /**
     * 假模型的一轮「推理」：还有脚本就返回工具调用，没有就返回最终答案。
     * 注意完全不看模型能力、不联网，纯粹用来验证主循环。
     */
    @Override
    public ChatResult chat(AgentContext ctx, List<Tool> tools) {
        if (cursor < script.size()) {
            return ChatResult.ofTools(List.of(script.get(cursor++)));
        }
        return ChatResult.ofText(answer.apply(ctx));
    }

    /**
     * 便捷构造一次工具调用，省去手写 JSON 字符串。
     *
     * 同时构造两份数据：argsJson（协议层要原样回填）和 args（本地工具直接用）。
     * 字符串会做 JSON 转义，否则代码片段里的换行和引号会造出非法 JSON。
     */
    public static ToolCall call(String id, String name, Map<String, Object> args) {
        StringBuilder sb = new StringBuilder("{");
        args.forEach((k, v) -> {
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(k).append("\":");
            if (v instanceof Number) sb.append(v);          // 数字不加引号
            else sb.append('"').append(escape(String.valueOf(v))).append('"');
        });
        sb.append('}');
        return new ToolCall(id, name, sb.toString(), new LinkedHashMap<>(args));
    }

    /** JSON 字符串转义。代码片段里必然有换行、引号、反斜杠，一个都不能漏 */
    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 取最后一次工具返回。
     * 方便假模型把 observation 原样反射进最终答案，证明「结果确实回填成功了」。
     */
    public static String lastObservation(AgentContext ctx) {
        List<Map<String, Object>> msgs = ctx.messages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if ("tool".equals(msgs.get(i).get("role"))) {
                return String.valueOf(msgs.get(i).get("content"));
            }
        }
        return "(无)";
    }
}
