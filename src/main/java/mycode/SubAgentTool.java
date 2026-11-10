package mycode;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * task 工具：把一个自包含的子任务派给子代理独立执行，只把最终结论带回来。
 *
 * 这是「主代理拆分长任务」的机制核心，思路对齐 Claude Code 的 Task 工具：
 *   1. 子代理 = 一个全新的 Agent 实例，有自己的上下文和工具表。它跑多少步，
 *      中间过程都留在它自己的上下文里，主代理的上下文只多一条结果消息 ——
 *      长任务不再撑爆主上下文，这就是派发的全部意义；
 *   2. 派发由模型自主决定（读 system 里的派发原则），主循环不需要任何特殊分支
 *      —— 对 Agent 来说 task 就是一个普通工具；
 *   3. 子代理不再拥有 task 工具（深度硬限制 1 层），防止递归派发烧钱。
 *
 * 两个刻意为之的边界：
 *   - 子代理没有流式输出：过程不上屏，主代理拿到完整结果后自行转述；
 *   - 子代理继承主代理的审批钩子：子代理执行危险命令照样要用户确认，
 *     它不能成为绕过权限的后门。
 * @author my
 */
public class SubAgentTool implements Tool, UsageContributor {

    /** 结果字符额度：这是精炼后的交付物，普通工具 1500 字的全局默认值不够用 */
    private static final int RESULT_LIMIT = 6000;

    /** 子代理步数上限。派给它的任务应当聚焦，比主代理的默认值收紧 */
    private static final int DEFAULT_MAX_STEPS = 15;

    /** 所有子代理共守的收尾约定，拼在各自定义的 system prompt 后面 */
    private static final String COMMON_RULES = """

            【工作约定】
            - 你看不到主对话历史，上面的任务说明就是全部输入，按它独立完成；
            - 只做任务说明里的事，不要顺手扩大范围；
            - 最终回答必须自包含：结论先行，附关键证据（文件、命令输出、数据）。
              主代理只能看到这一段话，你的中间过程不会传回去；
            - 找不到就明说找不到，失败就说失败：宁可结论不完整，
              也不要编造文件内容或数据把答案填满 —— 主代理会核对你给出的证据。
            """;

    /** 定义注册表：按 agent 参数选人 */
    private final AgentRegistry registry;

    /** 与主代理共用同一个模型客户端 */
    private final LlmClient llm;

    /** 主代理的工具表。派发时照它装配子代理的工具（按定义过滤、去掉 task 本身） */
    private final ToolRegistry parentTools;

    /**
     * 钩子工厂：入参是嵌套深度，返回该层代理要挂的钩子。
     * 由装配方（Shell）提供 —— 挂什么钩子属于装配决策，工具不该写死。
     */
    private final Function<Integer, List<Hook>> hooksForDepth;

    /** 子代理步数上限 */
    private final int maxSteps;

    /**
     * 子代理在主循环之外产生的用量，等主循环来取。
     * 并行扇出时多个线程同时累积，所以读写都走锁。
     */
    private Usage pending = Usage.ZERO;

    public SubAgentTool(AgentRegistry registry, LlmClient llm, ToolRegistry parentTools,
                        Function<Integer, List<Hook>> hooksForDepth) {
        this.registry = registry;
        this.llm = llm;
        this.parentTools = parentTools;
        this.hooksForDepth = hooksForDepth;
        this.maxSteps = configuredMaxSteps();
    }

    @Override
    public String name() {
        return "task";
    }

    @Override
    public String description() {
        return "把一个自包含的子任务派给子代理独立执行，只返回它的最终结论。"
                + "适用：可拆开的长任务、多文件调研、批量同类操作、互不依赖的子问题；"
                + "一两次工具调用就能完成的小事不要派。"
                + "prompt 必须自包含（子代理看不到对话历史），"
                + "agent 填系统提示词里列出的子代理类型，缺省 general。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Tool.schema(Map.of(
                        "description", Tool.str("3~5 个词的任务标题，用于展示与日志"),
                        "prompt", Tool.str("完整、自包含的任务说明：目标、涉及文件、期望交付物一次说清"),
                        "agent", Tool.str("子代理类型名，见系统提示词的子代理清单，缺省 general")),
                "description", "prompt");
    }

    @Override
    public int resultCharLimit() {
        return RESULT_LIMIT;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String prompt = String.valueOf(args.getOrDefault("prompt", "")).strip();
        if (prompt.isEmpty()) {
            throw new IllegalArgumentException(
                    "prompt 不能为空：子代理看不到对话历史，任务目标必须一次写全");
        }
        String type = String.valueOf(args.getOrDefault("agent", "general")).strip();
        AgentDefinition def = registry.find(type).orElseGet(registry::general);

        Agent child = Agent.builder(def.name())
                .llm(llm)
                .tools(childTools(def))
                .systemPrompt(def.systemPrompt() + COMMON_RULES)
                .hooks(hooksForDepth.apply(1).toArray(new Hook[0]))
                .maxSteps(maxSteps)
                .build();

        String answer = child.run(prompt);
        recordUsage(child.lastRunUsage());
        return answer.isBlank()
                ? "（子代理 " + def.name() + " 没有返回内容，可能没得出结论）"
                : answer;
    }

    @Override
    public synchronized Usage drainUsage() {
        Usage taken = pending;
        pending = Usage.ZERO;
        return taken;
    }

    /** 并行扇出时多个派发同时累积用量，加锁防串账 */
    private synchronized void recordUsage(Usage u) {
        pending = pending.plus(u);
    }

    /**
     * 装配子代理的工具表：从主代理的工具表里挑。
     * 两条过滤规则：定义里有白名单就只留白名单内的；task 本身一律不给（深度限制）。
     */
    private ToolRegistry childTools(AgentDefinition def) {
        ToolRegistry child = new ToolRegistry();
        for (Tool t : parentTools.all()) {
            if (t.name().equals(name())) continue;
            if (def.restrictsTools() && !def.allowedTools().contains(t.name())) continue;
            child.register(t);
        }
        return child;
    }

    /** 读 agent.properties 的 subAgentMaxSteps，非法值回退默认 */
    private static int configuredMaxSteps() {
        String v = AgentConfig.get("subAgentMaxSteps");
        if (v == null) return DEFAULT_MAX_STEPS;
        try {
            int n = Integer.parseInt(v);
            return n > 0 ? n : DEFAULT_MAX_STEPS;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_STEPS;
        }
    }
}
