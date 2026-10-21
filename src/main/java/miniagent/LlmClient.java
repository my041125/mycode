package miniagent;

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
}
