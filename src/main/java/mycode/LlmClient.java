package mycode;

import java.util.List;

/**
 * 模型调用抽象。
 *
 * 整个项目里只有这一个接口需要跟「厂商」打交道，换模型只需换实现类，
 * 主循环、工具层、上下文层一行都不用改。
 * 这也是 LangChain4j / Spring AI 都要先做一层 ChatModel 抽象的原因。
 *
 * 现有实现：
 *   OpenAiCompatibleClient —— OpenAI 兼容协议（OpenAI、DeepSeek、Kimi、智谱、通义、Ollama）
 *   AnthropicClient        —— Claude 原生 Messages 协议
 *   ScriptedLlmClient      —— 假模型，用于离线验证循环
 */
public interface LlmClient {

    /**
     * 发起一轮对话。
     *
     * 实现方要做三件事：把上下文转成该厂商的报文格式、发请求、把响应解析成 ChatResult。
     * 注意不要在这里处理「要不要继续循环」——那是 Agent 的职责。
     *
     * @param ctx   当前完整上下文（含 system、历史消息、之前的工具结果）
     * @param tools 本次允许模型调用的工具清单，为空表示这一轮不给工具
     * @return 本轮结果，可能是文本、可能是工具调用，也可能两者都有
     */
    ChatResult chat(AgentContext ctx, List<Tool> tools) throws Exception;

    /**
     * 流式版本：边生成边把文本增量推给 sink。
     *
     * 默认实现退化成「一次性拿到结果再整段推给 sink」，
     * 这样不支持流式的客户端（比如当前的 Anthropic 实现）不用写任何代码就能用上这个接口，
     * 代价只是没有逐字效果。真正的流式由具体客户端覆盖实现。
     *
     * @param sink 文本增量回调，为 null 时等价于调用 chat()
     */
    default ChatResult chatStream(AgentContext ctx, List<Tool> tools, StreamSink sink) throws Exception {
        ChatResult result = chat(ctx, tools);
        if (sink != null && result.text() != null) {
            sink.onText(result.text());
        }
        return result;
    }
}
