package mycode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
 * @author my
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

    /** 模型调用瞬时失败（限流/超时/断连）的最大重试次数 */
    private final int maxRetries;

    /** 是否允许一轮的多个工具调用并行执行（task 扇出的基础） */
    private final boolean parallelTools;

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
        this.maxRetries = b.maxRetries;
        this.parallelTools = b.parallelTools;
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
     * {@link #run(AgentContext, String, StreamSink)} —— 命令行交互模式就是这么做的。
     *
     * @param userInput 用户的自然语言任务描述
     * @return 最终回答；出错时返回以 ERROR: 开头的说明文本，不抛异常
     */
    public String run(String userInput) {
        return run(newContext(), userInput, null);
    }

    /**
     * 新建一份干净上下文：system 提示词 + 自动追加的工具清单。
     * 交互式对话需要把上下文留住，所以单独暴露出来。
     */
    public AgentContext newContext() {
        return AgentContext.of(systemPrompt + tools.describe());
    }

    /**
     * 带流式输出的执行。传 null 表示不要流式（等价于一次性拿到结果）。
     *
     * @param ctx       已有上下文
     * @param userInput 本轮输入
     * @param sink      文本增量回调；传 null 表示不要流式（等价于 run(ctx, input)）
     * @return 本轮最终回答
     */
    public String run(AgentContext ctx, String userInput, StreamSink sink) {
        // 压缩只在任务开始前做一次，绝不能放进循环里：
        // 压缩会重写 system 里的摘要并摘掉最老的对话，等于改写请求的前缀，
        // 而 DeepSeek 的前缀缓存按字节精确匹配 —— 循环内每步都压的话，
        // 上下文一旦超过阈值，之后每一步的前缀都和上一步不同，缓存步步全失效。
        // 挪到任务开始前：一次用户提问最多付一次「缓存作废」的代价。
        maybeCompress(ctx);
        ctx.user(userInput);
        return runLoop(ctx, sink);
    }

    /**
     * 带图片的一次任务（--image 用）。与 run 的唯一差别是用户消息按
     * OpenAI 协议的块数组形态写入（文字 + 图片 base64），其余流程完全一致。
     * 读写图片出错时返回 ERROR 文本，不抛异常 —— 与 run 的失败约定一致。
     */
    public String runWithImage(AgentContext ctx, String userInput, Path image, StreamSink sink) {
        maybeCompress(ctx);
        try {
            ctx.userWithImage(userInput, image);
        } catch (Exception e) {
            return "ERROR: 图片读取失败 - " + e.getMessage();
        }
        return runLoop(ctx, sink);
    }

    /** ReAct 主循环本体，run 与 runWithImage 共用 */
    private String runLoop(AgentContext ctx, StreamSink sink) {
        // 每次任务开始清零，统计的是「这一次 run」而不是整个会话
        lastRunUsage = Usage.ZERO;

        for (int step = 1; step <= maxSteps; step++) {
            // 循环内只留 trim 兜底：单条超大工具结果把上下文顶爆时丢最老的。
            // 正常情况下它是空操作，一个字节都不动，前缀（也就是缓存）保持原样
            ctx.trim(maxContextTokens);
            hooks.forEach(h -> h.beforeLlm(ctx));

            // 模型调用与瞬时故障重试都收在 callModel 里；失败到不可挽回时
            // 它返回一条以 ERROR 开头的文本结果，走「最终答案」路径如实上报，不抛异常
            ChatResult result = callModel(ctx, sink);
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

            // 1) 审批阶段：串行。确认交互要读共享的 stdin，不能并发。
            //    被拒的调用不执行，但必须回填一条 observation，否则模型不知道被拒会一直重试
            List<Dispatch> batch = new ArrayList<>();
            for (ToolCall call : result.toolCalls()) {
                Tool tool = tools.find(call.name()).orElse(null);
                boolean allowed = hooks.stream().allMatch(h -> h.beforeTool(call.name(), call.args()));
                batch.add(new Dispatch(call, tool, allowed));
            }

            // 2) 执行阶段：一批里放行了多个调用就并行跑（子代理扇出的基础），
            //    单个调用或关闭并行时串行。并行只发生在 dispatch 内部，
            //    终端输出由第 3 步按原顺序统一回填，所以界面不会乱
            Map<String, String> outputs = executeBatch(batch);

            // 3) 回填阶段：串行按原顺序截断、用量归集、通知钩子、写回上下文。
            //    最关键的 ReAct 一步在这里：模型下一轮才能「看到」工具返回了什么
            for (Dispatch d : batch) {
                String observation = d.allowed()
                        ? outputs.getOrDefault(d.call().id(), "")
                        : "ERROR: 该工具调用被审批钩子拦截，请换一种方式或告知用户";
                if (d.tool() instanceof UsageContributor contributor) {
                    // task 这类工具会在主循环之外另花 token（子代理自己跑了好几轮模型），
                    // 把挂起的用量并进本次 run 的总账，成本统计才不会漏记
                    lastRunUsage = lastRunUsage.plus(contributor.drainUsage());
                }
                final String obs = truncate(observation, d.tool());
                hooks.forEach(h -> h.afterTool(d.call().name(), obs));
                ctx.toolResult(d.call().id(), d.call().name(), obs);
            }
        }
        // 走到这里说明 maxSteps 用完还没收敛，属于异常情况，如实上报而不是编一个答案
        return "ERROR: 达到最大步数 " + maxSteps + " 仍未完成，已中止（防止无限循环烧 token）";
    }

    /** 一轮工具调用的执行单元：调用本身、解析出的工具实例、是否放行 */
    private record Dispatch(ToolCall call, Tool tool, boolean allowed) {
    }

    /**
     * 调一次模型，瞬时故障（限流/超时/断连）自动指数退避重试。
     *
     * 长任务跑到第十几步遇一次 429 就整轮报废太亏 —— 上下文都在本地，
     * 重试一次通常就过去了。401/403/400 这类配置或参数错误不重试，
     * 重试八次也是白等，立刻如实上报。
     *
     * 流式下只有还一个字都没吐时才允许重试：前一次的半截文本已经在
     * 终端上了，重试会造成重复输出。
     *
     * @return 模型结果；重试耗尽仍失败时返回一条以 ERROR 开头的文本结果
     */
    private ChatResult callModel(AgentContext ctx, StreamSink sink) {
        // 统计已流出的字数，包一层 sink，重试安全性靠它判断
        int[] emitted = {0};
        StreamSink counted = sink == null ? null
                : delta -> { emitted[0] += delta.length(); sink.onText(delta); };

        Exception failure = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                long wait = Retries.backoffMillis(attempt - 1);
                // lambda 只能捕获实际最终变量：attempt 与 failure 在循环里都会变，先复制
                final int retryNo = attempt;
                final String reason = failure == null ? "" : String.valueOf(failure.getMessage());
                hooks.forEach(h -> h.onModelRetry(retryNo, wait, reason));
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // 重试是一次真实的模型调用，转圈等界面重新拉起
                hooks.forEach(h -> h.beforeLlm(ctx));
            }
            try {
                // 有 sink 走流式，没有走普通接口；不支持流式的客户端由 LlmClient 默认实现兜底
                return counted == null
                        ? llm.chat(ctx, tools.all())
                        : llm.chatStream(ctx, tools.all(), counted);
            } catch (Exception e) {
                failure = e;
                if (emitted[0] > 0 || !Retries.transientFailure(e)) break;
            }
        }
        return ChatResult.ofText("ERROR: 模型调用失败 - "
                + (failure == null ? "被中断" : failure.getMessage()));
    }

    /**
     * 执行一批放行的工具调用，返回 callId -> observation。
     *
     * 多个调用并行时用固定小线程池；这要求工具实现无共享可变状态
     * （现有工具都满足，task 的用量累积已做同步）。
     * parallelTools=off 或只有单个调用时退回串行，行为与旧版完全一致。
     */
    private Map<String, String> executeBatch(List<Dispatch> batch) {
        List<Dispatch> runnable = batch.stream().filter(Dispatch::allowed).toList();
        Map<String, String> out = new LinkedHashMap<>();
        if (runnable.isEmpty()) return out;

        if (!parallelTools || runnable.size() == 1) {
            for (Dispatch d : runnable) {
                out.put(d.call().id(), tools.dispatch(d.call().name(), d.call().args()));
            }
            return out;
        }

        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(runnable.size(), 4), r -> {
                    Thread t = new Thread(r, "mycode-tool");
                    t.setDaemon(true);
                    return t;
                });
        try {
            Map<String, Future<String>> futures = new LinkedHashMap<>();
            for (Dispatch d : runnable) {
                futures.put(d.call().id(),
                        pool.submit(() -> tools.dispatch(d.call().name(), d.call().args())));
            }
            // 按提交顺序收结果：谁先谁后跑完不影响 observation 的次序
            for (Dispatch d : runnable) {
                try {
                    out.put(d.call().id(), futures.get(d.call().id()).get());
                } catch (ExecutionException e) {
                    out.put(d.call().id(), "ERROR: 工具执行失败 - "
                            + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    out.put(d.call().id(), "ERROR: 被中断");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    /**
     * 截断工具结果。宁可丢尾部，也不要让一条大结果挤掉整个对话历史。
     * 工具可以用 resultCharLimit() 申请更大的额度（如 task 返回子代理交付物），
     * 没申请就用全局默认。
     */
    private String truncate(String s, Tool tool) {
        if (s == null) return "";
        int limit = tool != null && tool.resultCharLimit() > 0
                ? tool.resultCharLimit()
                : maxToolOutputChars;
        return s.length() <= limit
                ? s
                : s.substring(0, limit) + "\n...(结果过长已截断)";
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
        /**
         * 最大步数，默认 20。
         * 早期设 8，实测复杂任务（读技能 → 写脚本 → 执行 → 验证）会被切在半路，
         * 模型还没产出文件就撞上限。可用 agent.properties 的 maxSteps 覆盖。
         */
        private int maxSteps = integer(AgentConfig.get("maxSteps"), 20);

        /** 瞬时失败最大重试次数，默认 3。agent.properties 的 maxRetries 可覆盖 */
        private int maxRetries = integer(AgentConfig.get("maxRetries"), 3);

        /** 一轮多个工具调用是否并行执行，默认开。agent.properties 的 parallelTools=off 关闭 */
        private boolean parallelTools = flag("parallelTools", true);

        /** 单条工具结果上限，默认 1500 字 */
        private int maxToolOutputChars = 1500;

        /**
         * 上下文上限，默认 60000 字（英文代码约 15k~20k token）。
         * 早期只有 12000：当时怕长上下文贵。后来引入前缀缓存优化后
         * 命中部分输入按约 1/10 计费（见 Tool.canonical），长前缀反而更省，
         * 所以提到 60000，长任务不再频繁触发压缩。agent.properties 的 maxContextTokens 可覆盖。
         */
        private int maxContextTokens = integer(AgentConfig.get("maxContextTokens"), 60000);
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
        /** 设置瞬时失败最大重试次数 */
        public Builder maxRetries(int n) { this.maxRetries = n; return this; }
        /** 开关一轮多个工具调用的并行执行 */
        public Builder parallelTools(boolean on) { this.parallelTools = on; return this; }
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

        /** 解析开关型配置：没配用默认值；off / false 关闭，其余视为开启 */
        private static boolean flag(String key, boolean def) {
            String v = AgentConfig.get(key);
            if (v == null) return def;
            return !"off".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v);
        }

        /** 构建。模型客户端是唯一必填项，缺了在启动时就报错，而不是等到调用时 NPE */
        public Agent build() {
            if (llm == null) throw new IllegalStateException("必须提供 LlmClient");
            return new Agent(this);
        }
    }
}
