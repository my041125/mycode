package mycode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内核部件 2：上下文 / 记忆。
 *
 * 内部直接维护 OpenAI 协议的消息数组，好处是换模型厂商时只需要换 LlmClient，
 * 这里一行不用改（AnthropicClient 会自己做协议转换）。
 *
 * 消息顺序铁律：
 *   system → user → assistant(带 tool_calls) → tool(带 tool_call_id) → assistant → tool → ...
 *
 * assistant 和随后的 tool 消息必须成对出现：少一条 tool，或 tool_call_id 对不上，
 * 大部分厂商的接口会直接返回 400。这是接真模型时最高频的坑。
 */
public class AgentContext {

    /** 消息列表。每条消息是一个 Map，键值遵循 OpenAI 协议（role / content / tool_calls / tool_call_id） */
    private final List<Map<String, Object>> messages = new ArrayList<>();

    /**
     * 创建上下文，第一条消息是 system。
     * 注意 system 只能是第一条，且只能有一条 —— 多转发给部分厂商会报错。
     */
    public static AgentContext of(String systemPrompt) {
        AgentContext ctx = new AgentContext();
        ctx.messages.add(Map.of("role", "system", "content", systemPrompt));
        return ctx;
    }

    /** 追加一条用户消息 */
    public void user(String text) {
        messages.add(Map.of("role", "user", "content", text));
    }

    /**
     * 追加一条 assistant 消息（即模型这一轮的回复）。
     *
     * 三种形态都要支持：
     *   1. 只有文字           -> 一般就是最终答案
     *   2. 只有工具调用        -> content 留空串，tool_calls 必填
     *   3. 文字 + 工具调用     -> 模型边说边做，两者都写进去
     *
     * 这里用 LinkedHashMap 而不是 Map.of：Map.of 不可变且不保证顺序，
     * 而报文里的字段顺序有时会被服务商严格校验。
     */
    public void assistant(String text, List<ToolCall> calls) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", text == null ? "" : text);
        if (calls != null && !calls.isEmpty()) {
            List<Map<String, Object>> arr = new ArrayList<>();
            for (ToolCall c : calls) {
                // 注意这里是 OpenAI 的嵌套结构：{id, type, function:{name, arguments}}
                // 其中 arguments 必须是字符串，不能是对象
                arr.add(Map.of("id", c.id(), "type", "function",
                        "function", Map.of("name", c.name(), "arguments", c.argsJson())));
            }
            msg.put("tool_calls", arr);
        }
        messages.add(msg);
    }

    /**
     * 把工具执行结果作为「观察」回填。
     * 这是 ReAct 循环里最关键的一步，也是新手最容易漏的一步 ——
     * 漏了它，模型下一轮完全不知道工具干了什么，会反复调同一个工具。
     *
     * @param callId  对应 assistant 消息里 tool_calls 的 id，必须一模一样
     * @param toolName 工具名，仅用于日志，不写进报文
     * @param content  工具返回的文本（已被 Agent 截断过）
     */
    public void toolResult(String callId, String toolName, String content) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "tool");
        msg.put("tool_call_id", callId);
        // 注意：不要额外塞 name 字段，部分厂商的兼容层会因此报 400
        msg.put("content", content);
        messages.add(msg);
    }

    /** 直接暴露消息列表，供 LlmClient 序列化成报文 */
    public List<Map<String, Object>> messages() {
        return messages;
    }

    /**
     * 粗略估算上下文长度。
     * 中文一个字约等于 1 token，英文约 4 字符 1 token，这里统一按字符数估，够用就行。
     * 精确计算需要引 tiktoken 之类的分词器，对教学项目不值当。
     *
     * 注意必须把 tool_calls 里的参数也算进来：assistant 消息的 content 常常是空串，
     * 真正的体积在 arguments 里（一次 edit_code 可能带几千字符的代码）。
     * 早期版本只统计 content，导致上下文被严重低估、裁剪永不触发，最终请求超限报 400。
     */
    public int estimateTokens() {
        int n = 0;
        for (Map<String, Object> m : messages) {
            Object c = m.get("content");
            if (c != null) n += c.toString().length();
            Object toolCalls = m.get("tool_calls");
            if (toolCalls != null) {
                // List<Map> 的 toString 已经包含了函数名和参数字符串，长度足够做估算
                n += toolCalls.toString().length();
            }
        }
        return n;
    }

    /**
     * 超长就丢最老的对话。
     *
     * 这是最小实现，够教学用。两个已知局限：
     *   1. 生产环境应该按「轮次」整段丢弃，而不是逐条删，避免留下孤儿 tool 消息；
     *   2. 更好的做法是做摘要压缩：让模型把老对话总结成一段话，而不是直接扔掉。
     * 保留前 3 条（system + 首轮问答）是为了不丢掉任务最初的意图。
     */
    public void trim(int maxTokens) {
        while (estimateTokens() > maxTokens && messages.size() > 3) {
            messages.remove(1);
            // 如果下一条是 tool 消息，它的发起方已经被删了，一并删掉避免孤儿消息导致 400
            while (messages.size() > 3 && "tool".equals(messages.get(1).get("role"))) {
                messages.remove(1);
            }
        }
    }

    /** 调试用：把上下文按 role 逐条打印出来，每行截到 100 字 */
    public void dump() {
        for (Map<String, Object> m : messages) {
            String role = String.valueOf(m.get("role"));
            Object content = m.get("content");
            String s = content == null ? "" : content.toString().replace("\n", " ");
            if (s.length() > 100) s = s.substring(0, 100) + "...";
            System.out.printf("    [%-9s] %s%n", role, s);
        }
    }
}
