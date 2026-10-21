package mycode;

import java.util.List;
import java.util.Map;

/**
 * 上下文压缩器：把「最老的一轮对话」换成一段摘要，而不是直接删掉。
 *
 * 为什么需要它：
 *   早期实现是 trim() —— 超过上限就从最老的开始删。问题是任务最初的意图、
 *   已经确认过的约束（比如「不要动注释风格」）往往就在最老那几条里，
 *   删掉之后 agent 会开始跑偏，而且用户完全看不出为什么。
 *
 * 现在的做法：整轮摘出来，交给模型压成几百字摘要，塞回 system 提示词里。
 * 这样上下文长度可控，任务意图也还在。
 *
 * 三个设计取舍：
 *   1. 必须整轮摘（user → assistant/tool → …），否则会出现
 *      「assistant 说要调工具、tool 结果却被删了」的协议违规；
 *   2. 摘要覆盖写进 system 的固定段落，而不是再追加一条消息，避免越压越长；
 *   3. 摘要调用失败（离线假模型、网络问题）时退回机械摘要，保证流程不中断。
 */
public class ContextCompressor {

    /** 一次最多喂给摘要器多少字符，太多会让摘要调用本身变贵 */
    private static final int MAX_FEED_CHARS = 12000;

    /** 给摘要本身预留的位置，避免「刚压完立刻又超」 */
    private static final int RESERVED_FOR_SUMMARY = 1200;

    /** 机械摘要的字数上限 */
    private static final int MECHANICAL_LIMIT = 900;

    /**
     * 压缩结果。
     *
     * @param removedMessages 移除了多少条消息
     * @param removedRounds   移除了多少轮对话
     * @param savedChars      净省下多少字（已扣掉摘要本身）
     * @param usage           摘要调用消耗的 token，调用方要累加进成本
     * @param usedModel       摘要是否真的由模型生成（false 表示走的兜底）
     */
    public record Stats(int removedMessages, int removedRounds, int savedChars,
                        Usage usage, boolean usedModel) {

        public static final Stats NONE = new Stats(0, 0, 0, Usage.ZERO, false);
    }

    /** 摘要器的系统提示词。要求写得很硬：只保留会影响后续决策的信息 */
    private static final String SUMMARIZER_PROMPT = """
            你是一个对话摘要器。把下面这段「较早的对话」压缩成简短摘要，供后续继续同一任务使用。

            只保留这些内容：
            - 用户的目标与已确认的约束（尤其是否定性的要求，例如「不要改注释」）
            - 已经查证过的事实与结论（例如某文件某方法的行为）
            - 已做过的修改及其位置
            - 尚未完成的事项

            丢弃：寒暄、重复的中间过程、大段代码原文、已经作废的尝试。

            要求：用中文，纯文本，不超过 300 字，不要加标题，不要写「以下是摘要」这类废话。
            """;

    /**
     * 执行压缩。
     *
     * @param ctx         要压缩的上下文（会被就地修改）
     * @param llm         用来生成摘要的模型；传 null 则走机械摘要
     * @param targetChars 希望压缩后的上下文长度（字）
     * @param keepRecent  至少保留最近多少条消息
     */
    public static Stats compress(AgentContext ctx, LlmClient llm, int targetChars, int keepRecent) {
        int before = ctx.estimateTokens();
        // 留出摘要的位置：目标是 targetChars，但摘到 budget 就停
        int budget = Math.max(400, targetChars - RESERVED_FOR_SUMMARY);

        int removedMessages = 0;
        int removedRounds = 0;
        StringBuilder dropped = new StringBuilder();

        while (ctx.estimateTokens() > budget && ctx.size() > 1 + keepRecent) {
            List<Map<String, Object>> round = ctx.drainOldestRound(keepRecent);
            if (round.isEmpty()) break;   // 只剩当前一轮，不能再摘
            removedRounds++;
            removedMessages += round.size();
            dropped.append(render(round));
            if (dropped.length() >= MAX_FEED_CHARS) break;
        }
        if (removedRounds == 0) return Stats.NONE;

        Summary summary = summarize(llm, ctx.summary(), dropped.toString());
        ctx.setSummary(summary.text());

        return new Stats(removedMessages, removedRounds,
                Math.max(0, before - ctx.estimateTokens()), summary.usage(), summary.fromModel());
    }

    /** 摘要结果：文本 + 用量 + 是否来自模型 */
    private record Summary(String text, Usage usage, boolean fromModel) {
    }

    /**
     * 生成摘要。优先用模型，任何异常都退回机械摘要。
     * 压缩是「清理动作」，它自己绝不允许把主流程搞挂。
     */
    private static Summary summarize(LlmClient llm, String previous, String dropped) {
        if (llm != null) {
            try {
                AgentContext sc = AgentContext.of(SUMMARIZER_PROMPT);
                sc.user(buildPrompt(previous, dropped));
                ChatResult r = llm.chat(sc, List.of());
                if (r != null && r.text() != null && !r.text().isBlank()) {
                    return new Summary(r.text().strip(), r.usage(), true);
                }
            } catch (Exception ignore) {
                // 网络失败、鉴权失败都在这里兜住，下面走机械摘要
            }
        }
        return new Summary(mechanical(previous, dropped), Usage.ZERO, false);
    }

    private static String buildPrompt(String previous, String dropped) {
        String prev = (previous == null || previous.isBlank()) ? "（无）" : previous;
        return "已有的摘要：\n" + prev + "\n\n需要并入摘要的新对话：\n" + dropped
                + "\n请输出合并后的新摘要（覆盖旧摘要，不要重复罗列）。";
    }

    /**
     * 机械摘要：不经过模型，只做结构化裁剪。
     * 效果远不如模型摘要，但胜在永远不会失败，且完全可预期（方便测试）。
     */
    private static String mechanical(String previous, String dropped) {
        StringBuilder sb = new StringBuilder();
        if (previous != null && !previous.isBlank()) {
            sb.append(previous.strip()).append('\n');
        }
        String body = dropped.strip();
        if (body.length() > MECHANICAL_LIMIT) {
            body = body.substring(0, MECHANICAL_LIMIT) + "…";
        }
        sb.append("（以下为自动摘要，模型摘要不可用时的兜底）\n").append(body);
        String out = sb.toString();
        return out.length() > MECHANICAL_LIMIT + 400
                ? out.substring(out.length() - (MECHANICAL_LIMIT + 400))
                : out;
    }

    /**
     * 把消息渲染成摘要器能读的文本。
     * 单条内容截断到 400 字：摘要只需要知道「聊了什么」，不需要原样复现大段代码。
     */
    private static String render(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : messages) {
            String role = String.valueOf(m.get("role"));
            Object content = m.get("content");
            String text = content == null ? "" : String.valueOf(content).replace("\n", " ").strip();
            if (text.length() > 400) text = text.substring(0, 400) + "…";
            sb.append("- ").append(label(role)).append(": ").append(text).append('\n');

            Object calls = m.get("tool_calls");
            if (calls != null) {
                String c = String.valueOf(calls).replace("\n", " ");
                if (c.length() > 200) c = c.substring(0, 200) + "…";
                sb.append("  （工具调用: ").append(c).append("）\n");
            }
        }
        return sb.toString();
    }

    /** 把协议里的 role 换成中文，方便摘要器理解 */
    private static String label(String role) {
        return switch (role) {
            case "user" -> "用户";
            case "assistant" -> "助手";
            case "tool" -> "工具结果";
            case "system" -> "系统";
            default -> role;
        };
    }

    private ContextCompressor() {
    }
}
