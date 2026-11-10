package mycode;

/**
 * 一次模型调用的 token 用量。
 *
 * 各家协议字段名不同，但核心都是「输入 + 输出」：
 *   OpenAI 兼容：usage.prompt_tokens / usage.completion_tokens
 *   Anthropic ：usage.input_tokens  / usage.output_tokens
 *
 * 第三个数 cacheReadTokens 是「输入里命中服务商前缀缓存的部分」：
 *   DeepSeek  ：usage.prompt_cache_hit_tokens（未命中部分是 prompt_cache_miss_tokens）
 *   OpenAI    ：usage.prompt_tokens_details.cached_tokens
 *   Anthropic ：usage.cache_read_input_tokens
 * DeepSeek 的缓存自动生效、无需申请，但命中部分按约 1/10 的输入价计费，
 * 所以想省钱就要盯着它：命中率越高、且前缀越稳定，账单越便宜。
 *
 * 单独抽成 record 而不是塞两个 int 到 ChatResult 里，是为了能直接做累加：
 *   sessionUsage = sessionUsage.plus(result.usage())
 *
 * @param promptTokens     输入 token 总数（DeepSeek 的口径里已包含命中缓存的部分）
 * @param completionTokens 输出 token 数
 * @param cacheReadTokens  输入中命中缓存的部分；服务商不报告时为 0
 * @author my
 */
public record Usage(int promptTokens, int completionTokens, int cacheReadTokens) {

    /** 零用量。离线假模型、流式响应没带 usage 时都用它兜底 */
    public static final Usage ZERO = new Usage(0, 0, 0);

    /** 兼容两参构造：老调用点没有缓存数据，命中数按 0 处理 */
    public Usage(int promptTokens, int completionTokens) {
        this(promptTokens, completionTokens, 0);
    }

    /** 总 token 数 */
    public int total() {
        return promptTokens + completionTokens;
    }

    /** 累加，用于统计整场会话 */
    public Usage plus(Usage other) {
        if (other == null) return this;
        return new Usage(promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                cacheReadTokens + other.cacheReadTokens);
    }

    @Override
    public String toString() {
        String cache = cacheReadTokens > 0
                ? "（其中缓存命中 " + cacheReadTokens + "）"
                : "";
        return promptTokens + " 输入 + " + completionTokens + " 输出 = " + total() + " tokens" + cache;
    }
}
