package mycode;

import java.util.List;

/**
 * 一轮模型调用的结果。
 *
 * 三种可能，主循环靠 hasToolCalls() 区分：
 *   1. 只有文本        -> 模型认为可以回答了，循环结束
 *   2. 只有工具调用    -> 继续执行工具
 *   3. 文本 + 工具调用 -> 模型边说边做，两者都要保留
 *
 * 这里刻意做成 public：之前它是包级私有的，导致外部包无法实现 LlmClient 和 Hook，
 * 框架只能在自己包里用 —— 这是个实打实的 API 设计缺陷。
 *
 * @param text      模型输出的文字，可能为 null
 * @param toolCalls 模型要求的工具调用，可能为空列表
 * @param usage     本轮 token 用量，拿不到时为 Usage.ZERO
 */
public record ChatResult(String text, List<ToolCall> toolCalls, Usage usage) {

    /** 兼容旧写法：不关心用量的地方可以只传两个参数 */
    public ChatResult(String text, List<ToolCall> toolCalls) {
        this(text, toolCalls, Usage.ZERO);
    }

    /** 构造「纯文本回答」结果 */
    public static ChatResult ofText(String text) {
        return new ChatResult(text, List.of(), Usage.ZERO);
    }

    /** 构造「要求调用工具」结果 */
    public static ChatResult ofTools(List<ToolCall> calls) {
        return new ChatResult(null, calls, Usage.ZERO);
    }

    /** 这一轮有没有工具调用 —— 主循环靠它决定是继续还是收工 */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /** 带上用量信息，返回新实例（record 不可变，只能换一个） */
    public ChatResult withUsage(Usage u) {
        return new ChatResult(text, toolCalls, u == null ? Usage.ZERO : u);
    }
}
