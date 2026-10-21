package mycode;

/**
 * 会话：把「一份上下文 + 一个 agent」包起来，支持多轮连续对话。
 *
 * 为什么单独抽一个类：Agent 本身是无状态的（每次 run 只负责跑一轮 ReAct），
 * 而交互式命令行需要「记住上一轮说了什么」。把状态放在会话层，
 * Agent 就能同时被单次任务和连续对话两种场景复用，互不干扰。
 *
 * 一个会话 = 一份 AgentContext + 累计轮数 + 起始时间，
 * 顺手提供上下文长度和耗时，方便 /cost、/status 这类命令展示。
 */
public class ChatSession {

    /** 真正执行任务的 agent */
    private final Agent agent;

    /** 当前上下文。每轮问答和工具结果都累积在这里，这就是「记忆」 */
    private AgentContext ctx;

    /** 已完成轮数，用于界面展示 */
    private int turns = 0;

    /** 整场会话累计的 token 用量，用于 /cost 展示真实成本 */
    private Usage totalUsage = Usage.ZERO;

    /** 会话创建时间，用于算总耗时 */
    private final long startMillis = System.currentTimeMillis();

    /**
     * @param agent 执行任务的 agent
     */
    public ChatSession(Agent agent) {
        this.agent = agent;
        this.ctx = agent.newContext();
    }

    /**
     * 提一个问题，返回本轮最终回答。上下文会自动累积。
     *
     * @param input 用户输入
     * @return 最终回答
     */
    public String ask(String input) {
        return ask(input, null);
    }

    /**
     * 带流式的提问：文字边生成边推给 sink，答案仍然完整返回。
     *
     * @param input 用户输入
     * @param sink  文本增量回调，null 表示不流式
     * @return 最终回答
     */
    public String ask(String input, StreamSink sink) {
        turns++;
        String answer = agent.run(ctx, input, sink);
        // 一次提问可能内部调好几轮模型，这里把整轮消耗一起累计
        totalUsage = totalUsage.plus(agent.lastRunUsage());
        return answer;
    }

    /** 整场会话累计用量 */
    public Usage totalUsage() {
        return totalUsage;
    }

    /** 清空记忆，重新开始（对应 /clear 命令） */
    public void reset() {
        this.ctx = agent.newContext();
        this.turns = 0;
        this.totalUsage = Usage.ZERO;
    }

    /** 已完成轮数 */
    public int turns() {
        return turns;
    }

    /** 当前上下文的估算字数，用来判断是不是快撑满了 */
    public int contextChars() {
        return ctx.estimateTokens();
    }

    /** 会话已持续的秒数 */
    public long elapsedSeconds() {
        return (System.currentTimeMillis() - startMillis) / 1000;
    }
}
