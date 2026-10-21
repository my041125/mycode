package mycode;

/**
 * 一次模型调用的 token 用量。
 *
 * 各家协议字段名不同，但都是这两个数：
 *   OpenAI 兼容：usage.prompt_tokens / usage.completion_tokens
 *   Anthropic ：usage.input_tokens  / usage.output_tokens
 *
 * 单独抽成 record 而不是塞两个 int 到 ChatResult 里，是为了能直接做累加：
 *   sessionUsage = sessionUsage.plus(result.usage())
 */
public record Usage(int promptTokens, int completionTokens) {

    /** 零用量。离线假模型、流式响应没带 usage 时都用它兜底 */
    public static final Usage ZERO = new Usage(0, 0);

    /** 总 token 数 */
    public int total() {
        return promptTokens + completionTokens;
    }

    /** 累加，用于统计整场会话 */
    public Usage plus(Usage other) {
        if (other == null) return this;
        return new Usage(promptTokens + other.promptTokens,
                completionTokens + other.completionTokens);
    }

    @Override
    public String toString() {
        return promptTokens + " 输入 + " + completionTokens + " 输出 = " + total() + " tokens";
    }
}
