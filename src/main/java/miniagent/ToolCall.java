package miniagent;

import java.util.List;
import java.util.Map;

/**
 * 模型要求调用某个工具。
 *
 * 字段说明：
 *   id       —— 本次调用的唯一标识，回填结果时靠它配对（Anthropic 叫 tool_use_id）
 *   name     —— 工具名
 *   argsJson —— 模型返回的原始参数字符串，必须原样保留
 *   args     —— 解析后的参数，方便工具直接取用
 *
 * 为什么要同时保留 argsJson 和 args：
 * OpenAI 协议要求把 assistant 的 tool_calls 原样发回给下一轮，
 * 如果只存解析后的 Map 再序列化，字段顺序和数字格式可能与原文不同，
 * 部分严格要求一致性的服务商会因此报错。args 只是给本地代码用的便利视图。
 */
public record ToolCall(String id, String name, String argsJson, Map<String, Object> args) {
}

/**
 * 一轮模型调用的结果。
 *
 * 三种可能：
 *   1. 只有文本        -> 模型认为可以回答了，循环结束
 *   2. 只有工具调用    -> 继续执行工具
 *   3. 文本 + 工具调用 -> 模型边说边做，两者都要保留
 */
record ChatResult(String text, List<ToolCall> toolCalls) {

    /** 构造「纯文本回答」结果 */
    static ChatResult ofText(String text) {
        return new ChatResult(text, List.of());
    }

    /** 构造「要求调用工具」结果 */
    static ChatResult ofTools(List<ToolCall> calls) {
        return new ChatResult(null, calls);
    }

    /** 判断这一轮有没有工具调用 —— 主循环靠它决定是继续还是收工 */
    boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
