package mycode;

import java.util.List;

/**
 * 内核部件 3：ReAct 主循环。整个 agent 的本质就是这个 for 循环。
 *
 *   目标 → [思考 → 行动 → 观察] × n → 最终答案
 *
 * 三个容易踩的坑，全部在这里处理掉了：
 *   1. 循环必须有 maxSteps 上限，否则模型抽风会无限调工具烧钱；
 *   2. 没有工具调用 ≠ 出错了，它就是「模型认为可以回答了」，直接返回即可；
 *   3. 工具结果必须截断，一次大结果就能把上下文撑爆，导致模型跑偏。
 *
 * 之所以用 for 而不是 while(true)：步数上限是硬约束，写在循环条件里比写在循环体里更难漏。
 */
public class Agent {

    /** agent 名称，只用于日志输出，方便同时跑多个 agent 时区分子任务 */
    private final String name;

    /** 模型客户端。这是唯一与厂商耦合的地方 */
    private final LlmClient llm;

    /** 工具注册表，每轮要把全量工具清单传给模型 */
    private final ToolRegistry tools;

    /** 系统提示词。工具清单会被自动追加到它后面 */
    private final String systemPrompt;

    /** 拦截钩子链，按注册顺序依次执行 */
    private final List<Hook> hooks;

    /** 最大步数：模型最多来回几轮，到了还没结论就强制中止 */
    private final int maxSteps;

    /** 单次工具结果的字符上限，超出截断 */
    private final int maxToolOutputChars;

    /** 上下文长度上限（按字数粗估），超出丢弃最老的对话 */
    private final int maxContextTokens;

    /** 最近一次 run 累计消耗的 token（可能跨多轮工具调用），供会话做成本统计 */
    private Usage lastRunUsage = Usage.ZERO;

    /** 是否启用上下文压缩（关掉就退回「删最老消息」的粗暴策略） */
    private final boolean compressionEnabled;

    /** 压缩触发比例：上下文超过 maxContextTokens × 该值就开始压缩 */
    private final double compressRatio;

    /** 压缩时至少保留最近多少条消息，保护当前任务的上下文 */
    private final int compressKeep;

    private Agent(Builder b) {
        this.name = b.name;
        this.llm = b.llm;
        this.tools = b.tools;
        this.systemPrompt = b.systemPrompt;
        this.hooks = b.hooks;
        this.maxSteps = b.maxSteps;
        this.maxToolOutputChars = b.maxToolOutputChars;
        this.maxContextTokens = b.maxContextTokens;
        this.compressionEnabled = b.compressionEnabled;
        this.compressRatio = b.compressRatio;
        this.compressKeep = b.compressKeep;
    }

    /**
     * 执行一次完整任务（单轮）。
     *
     * 每次调用都会新建一份上下文，也就是说两次 run 之间不共享记忆。
     * 需要多轮对话请用 {@link #newContext()} 拿一份上下文，再调
     * {@link #run(AgentContext, String)} —— 命令行交互模式就是这么做的。
     *
     * @param userInput 用户的自然语言任务描述
     * @return 最终回答；出错时返回以 ERROR: 开头的说明文本，不抛异常
     */
    public String run(String userInput) {
        return run(newContext(), userInput);
    }

    /**
     * 新建一份干净上下文：system 提示词 + 自动追加的工具清单。
     * 交互式对话需要把上下文留住，所以单独暴露出来。
     */
    public AgentContext newContext() {
        return AgentContext.of(systemPrompt + tools.describe());
    }

    /**
     * 在一份已有上下文上继续执行 —— 这是多轮对话的关键。
     *
     * 上下文里保留了之前的问答和工具结果，模型因此能记住上一轮说了什么。
     *
     * @param ctx       已有的上下文，会被就地追加消息
     * @param userInput 本轮用户输入
     * @return 本轮最终回答
     */
    public String run(AgentContext ctx, String userInput) {
        return run(ctx, userInput, null);
    }

    /**
     * 带流式输出的执行。
     *
     * @param ctx       已有上下文
     * @param userInput 本轮输入
     * @param sink      文本增量回调；传 null 表示不要流式（等价于 run(ctx, input)）
     * @return 本轮最终回答
     */
    public String run(AgentContext ctx, String userInput, StreamSink sink) {
        ctx.user(userInput);
        // 每次任务开始清零，统计的是「这一次 run」而不是整个会话
        lastRunUsage = Usage.ZERO;

        for (int step = 1; step <= maxSteps; step++) {
            // 每轮开始先处理上下文膨胀：
            // 优先「压缩成摘要」，压完还超限才走 trim 丢弃（最后兜底）
            maybeCompress(ctx);
            ctx.trim(maxContextTokens);
            hooks.forEach(h -> h.beforeLlm(ctx));

            ChatResult result;
            try {
                // 有 sink 就走流式接口，没有就走普通接口。
                // 不支持流式的客户端由 LlmClient 的默认实现兜底，这里不用关心。
                result = sink == null
                        ? llm.chat(ctx, tools.all())
                        : llm.chatStream(ctx, tools.all(), sink);
            } catch (Exception e) {
                // 模型调用失败不重试到死：一次 401/限流重试八次只是白烧时间，直接如实上报
                return "ERROR: 模型调用失败 - " + e.getMessage();
            }
            hooks.forEach(h -> h.afterLlm(result));
            // 累计本轮用量：一次任务可能调好几轮模型，成本要加总
            lastRunUsage = lastRunUsage.plus(result.usage());

            // 分支 A：模型不再要求调用工具 → 这就是最终答案，退出循环
            if (!result.hasToolCalls()) {
                return result.text() == null ? "" : result.text();
            }

            // 分支 B：先把「我想调什么」写回上下文，再逐个执行。
            // 顺序不能反：assistant 消息必须在 tool 消息之前，否则协议校验不过
            ctx.assistant(result.text(), result.toolCalls());

            for (ToolCall call : result.toolCalls()) {
                // 审批钩子：任一钩子返回 false 就拦住这次调用。
                // 注意被拦截也要回填一条 observation，否则模型不知道自己被拒了，会一直重试
                boolean allowed = hooks.stream().allMatch(h -> h.beforeTool(call.name(), call.args()));
                String observation = allowed ? tools.dispatch(call.name(), call.args())
                        : "ERROR: 该工具调用被审批钩子拦截，请换一种方式或告知用户";
                // 必须用 final 变量接住，否则下面的 lambda 无法捕获（Java 闭包要求 effectively final）
                final String obs = truncate(observation);
                hooks.forEach(h -> h.afterTool(call.name(), obs));

                // 最关键的一步：把观察结果回填进上下文，
                // 下一轮模型才能「看到」工具到底返回了什么，这是 ReAct 的核心
                ctx.toolResult(call.id(), call.name(), obs);
            }
        }
        // 走到这里说明 maxSteps 用完还没收敛，属于异常情况，如实上报而不是编一个答案
        return "ERROR: 达到最大步数 " + maxSteps + " 仍未完成，已中止（防止无限循环烧 token）";
    }

    /** 截断工具结果。宁可丢尾部，也不要让一条大结果挤掉整个对话历史 */
    private String truncate(String s) {
        if (s == null) return "";
        return s.length() <= maxToolOutputChars
                ? s
                : s.substring(0, maxToolOutputChars) + "\n...(结果过长已截断)";
    }

    /** 最近一次 run 的 token 用量，会话层用它累计总成本 */
    public Usage lastRunUsage() {
        return lastRunUsage;
    }

    /**
     * 上下文超阈值时执行压缩。
     *
     * 注意压缩本身要调一次模型，所以只在真正超限时才做 ——
     * 每轮都压会让成本和延迟都翻倍。
     */
    private void maybeCompress(AgentContext ctx) {
        if (!compressionEnabled) return;
        int target = (int) (maxContextTokens * compressRatio);
        if (ctx.estimateTokens() <= target) return;

        ContextCompressor.Stats stats = ContextCompressor.compress(ctx, llm, target, compressKeep);
        if (stats.removedMessages() == 0) return;

        // 摘要调用也花钱，算进本次任务成本
        lastRunUsage = lastRunUsage.plus(stats.usage());
        hooks.forEach(h -> h.onContextCompressed(
                stats.removedMessages(), stats.savedChars(), ctx.summary()));
    }

    /**
     * 立即压缩一次（供 /compress 命令手动触发）。
     *
     * @param force true 表示不管当前有没有超阈值，都压到「当前体量的一半」；
     *              false 表示按阈值判断（和自动压缩一致）
     * @return 压缩统计；没有可压内容时 removedMessages 为 0
     */
    public ContextCompressor.Stats compressNow(AgentContext ctx, boolean force) {
        int target = force
                // 强制模式：压到当前的一半，但留个下限，避免把上下文压成一句话
                ? Math.max(600, ctx.estimateTokens() / 2)
                : (int) (maxContextTokens * compressRatio);
        ContextCompressor.Stats stats = ContextCompressor.compress(ctx, llm, target, compressKeep);
        if (stats.removedMessages() > 0) {
            lastRunUsage = lastRunUsage.plus(stats.usage());
            hooks.forEach(h -> h.onContextCompressed(
                    stats.removedMessages(), stats.savedChars(), ctx.summary()));
        }
        return stats;
    }

    /** 压缩触发阈值（字），供界面展示 */
    public int compressThreshold() {
        return (int) (maxContextTokens * compressRatio);
    }

    // ---------------- Builder：参数太多，用建造者模式避免构造器一长串 ----------------

    /** 创建建造器，参数是 agent 名称 */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** 建造器，每个 setter 都返回 this，可以链式调用 */
    public static class Builder {
        /** agent 名称，必填 */
        private final String name;
        /** 模型客户端，必填 */
        private LlmClient llm;
        /** 工具注册表，默认空表 */
        private ToolRegistry tools = new ToolRegistry();
        /** 系统提示词，默认一句兜底 */
        private String systemPrompt = "你是一个严谨的助手。";
        /** 钩子，默认不挂任何钩子 */
        private List<Hook> hooks = List.of();
        /** 最大步数，默认 8。一般任务 4~6 步足够，复杂任务再调大 */
        private int maxSteps = 8;
        /** 单条工具结果上限，默认 1500 字 */
        private int maxToolOutputChars = 1500;
        /** 上下文上限，默认 12000 字（约 12k token） */
        private int maxContextTokens = 12000;
        /** 是否启用压缩，默认开 */
        private boolean compressionEnabled = true;
        /** 压缩触发比例，默认 70%。可用 agent.properties 的 compressRatio 覆盖 */
        private double compressRatio = num(AgentConfig.get("compressRatio"), 0.7);
        /** 压缩时保留的最近消息条数，默认 4。可用 compressKeep 覆盖 */
        private int compressKeep = integer(AgentConfig.get("compressKeep"), 4);

        private Builder(String name) {
            this.name = name;
        }

        /** 设置模型客户端 */
        public Builder llm(LlmClient llm) { this.llm = llm; return this; }
        /** 设置工具注册表 */
        public Builder tools(ToolRegistry t) { this.tools = t; return this; }
        /** 设置系统提示词 */
        public Builder systemPrompt(String p) { this.systemPrompt = p; return this; }
        /** 挂载钩子，可以传多个，按顺序执行 */
        public Builder hooks(Hook... h) { this.hooks = List.of(h); return this; }
        /** 设置最大步数 */
        public Builder maxSteps(int n) { this.maxSteps = n; return this; }
        /** 设置单条工具结果上限 */
        public Builder maxToolOutputChars(int n) { this.maxToolOutputChars = n; return this; }
        /** 设置上下文上限 */
        public Builder maxContextTokens(int n) { this.maxContextTokens = n; return this; }
        /** 开关上下文压缩 */
        public Builder compressionEnabled(boolean on) { this.compressionEnabled = on; return this; }
        /** 设置压缩触发比例（0~1） */
        public Builder compressRatio(double r) { this.compressRatio = r; return this; }
        /** 设置压缩时保留的最近消息条数 */
        public Builder compressKeep(int n) { this.compressKeep = n; return this; }

        /** 解析配置里的比例，非法或越界就回到默认值 */
        private static double num(String v, double def) {
            if (v == null || v.isBlank()) return def;
            try {
                double d = Double.parseDouble(v.strip());
                return (d > 0.2 && d < 1.0) ? d : def;
            } catch (NumberFormatException e) {
                return def;
            }
        }

        /** 解析配置里的整数，非法就回到默认值 */
        private static int integer(String v, int def) {
            if (v == null || v.isBlank()) return def;
            try {
                int n = Integer.parseInt(v.strip());
                return n > 0 ? n : def;
            } catch (NumberFormatException e) {
                return def;
            }
        }

        /** 构建。模型客户端是唯一必填项，缺了在启动时就报错，而不是等到调用时 NPE */
        public Agent build() {
            if (llm == null) throw new IllegalStateException("必须提供 LlmClient");
            return new Agent(this);
        }
    }
}
