package miniagent;

import java.util.List;
import java.util.Map;

/**
 * 联网搜索演示。
 *
 * 重点在于：主循环、工具注册表、上下文、钩子一行都没改，
 * 只是多注册了两个工具（web_search、fetch_page）——
 * 这就是「搜索是工具而不是模型能力」这个设计的直接收益。
 *
 * 运行：
 *   mvn -q exec:java -Dexec.args="--search"                    离线跑通「搜索 → 引用」
 *   set BOCHA_API_KEY=sk-xxx                                   国内中文搜索，推荐
 *   set TAVILY_API_KEY=tvly-xxx                                备选
 *   mvn -q exec:java -Dexec.args="--search --real"             真联网
 */
public class SearchDemo {

    /** 每次搜索返回几条。3 条足够回答问题，又不至于把上下文占满 */
    private static final int TOP_K = 3;

    /**
     * 运行搜索演示。
     *
     * @param offline 是否离线模式（用假搜索 + 假模型）
     * @param args    命令行参数，真实模式下用来决定用哪家模型
     */
    public static void run(boolean offline, String[] args) throws Exception {
        SearchProvider provider = resolveProvider(offline);

        // 两个工具：搜索 + 抓网页正文
        ToolRegistry tools = new ToolRegistry()
                .register(new WebSearchTool(provider, TOP_K))   // 条数由我们控，模型无权决定
                .register(new FetchPageTool());                 // 摘要不够时顺着 URL 读全文

        LlmClient llm;
        if (offline) {
            // 离线：脚本化假模型，固定先搜一次再作答，用来验证「搜索结果回填 + 引用」链路
            llm = ScriptedLlmClient.of(
                    List.of(ScriptedLlmClient.call("call_1", "web_search",
                            Map.of("query", "零陵古城 夜间灯光秀 时间"))),
                    ctx -> """
                            （离线模拟回答）零陵古城免大门票，内部小景点单独收费；
                            夜间灯光秀每周五、六 19:30 开始，持续约 40 分钟 [2]。
                            来源: https://travel.example.com/lingling-guide

                            最后一条工具返回：""" + "\n" + ScriptedLlmClient.lastObservation(ctx));
        } else {
            llm = LlmFactory.create(args);
        }

        Agent agent = Agent.builder("联网文旅助手")
                .llm(llm)
                .tools(tools)
                .systemPrompt("""
                        你是乡村文旅助手，具备联网搜索能力。
                        规则：
                        1. 景点、商户、预约等系统内部数据用内部工具查，不要浪费搜索额度；
                        2. 需要实时信息（活动档期、开放时间、票价变动、交通管制）时调用 web_search；
                        3. 引用搜索结果必须标 [编号] 并附 URL；摘要没覆盖的细节不要臆测；
                        4. 搜索没结果时如实告知用户，不要用常识补全。
                        """)
                .hooks(new LoggingHook())
                .maxSteps(6)
                .build();

        System.out.println("\n搜索服务: " + provider.name() + (offline ? "（离线模拟）" : "（真实）"));
        System.out.println("\n============== 最终回答 ==============");
        System.out.println(agent.run("零陵古城的夜间灯光秀一般什么时候开始？需要另外买票吗？"));
        System.out.println("=====================================");
    }

    /**
     * 决定用哪个搜索服务商。
     *
     * 优先级：离线模拟 > 博查 > Tavily。
     * 博查在前是因为它国内可直连、中文网页覆盖更好；
     * 都没配就明确报错并给出解决方式，而不是悄悄退化成无搜索。
     *
     * @param offline 是否离线模式
     * @return 搜索服务商实例
     */
    private static SearchProvider resolveProvider(boolean offline) {
        if (offline) return new MockSearchProvider();
        String bocha = System.getenv("BOCHA_API_KEY");
        String tavily = System.getenv("TAVILY_API_KEY");
        if (bocha != null && !bocha.isBlank()) return new BochaSearchProvider(bocha);
        if (tavily != null && !tavily.isBlank()) return new TavilySearchProvider(tavily);
        throw new IllegalStateException(
                "未找到搜索密钥。设置 BOCHA_API_KEY 或 TAVILY_API_KEY，或加 --mock 离线运行");
    }
}
