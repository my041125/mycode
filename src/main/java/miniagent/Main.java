package miniagent;

import java.util.List;
import java.util.Map;

/**
 * 启动入口：所有功能的统一入口，按命令行参数分发到不同场景。
 *
 *   mvn -q compile
 *   mvn -q exec:java -Dexec.args="--mock"              离线验证主循环（门票场景）
 *   mvn -q exec:java -Dexec.args="--search"            离线验证联网搜索
 *   mvn -q exec:java -Dexec.args="--dump"              对比 OpenAI / Anthropic 报文
 *   mvn -q exec:java -Dexec.args="--config"            查看生效的模型配置
 *   mvn -q exec:java -Dexec.args="--code-demo"         离线验证代码编辑
 *   mvn -q exec:java -Dexec.args="--real"              接真模型
 *
 * 设计说明：这里刻意不做任何业务逻辑，只做「装配」——
 * 建工具、选模型、挂钩子、跑一次。想换成 Web 服务，把这套装配搬到 Controller 里就行。
 */
public class Main {

    /**
     * 程序入口，按参数分发。
     *
     * @param args 命令行参数，可组合，例如 "--search --real --provider=deepseek"
     */
    public static void main(String[] args) throws Exception {
        List<String> argv = List.of(args);

        // 各演示场景：先判断再分发，避免每个场景都写一遍模型初始化
        // 帮助信息放在 Java 里输出而不是写在 run.bat 里：
        // cmd 解析含 UTF-8 中文的批处理文件有已知缺陷，让 Java 打印编码才可靠
        if (argv.contains("--help") || argv.contains("-h")) {
            printHelp();
            return;
        }
        if (argv.contains("--search")) {
            SearchDemo.run(!argv.contains("--real"), args);
            return;
        }
        if (argv.contains("--dump")) {
            RequestDump.run();
            return;
        }
        if (argv.contains("--config")) {
            printConfig(args);
            return;
        }
        if (argv.contains("--code") || argv.contains("--code-demo")) {
            CodingDemo.run(args);
            return;
        }

        // 默认场景：乡村文旅行程助手。没有 --real 就用假模型离线跑
        boolean mock = !argv.contains("--real");

        // ---------------- 装配三个演示工具 ----------------
        ToolRegistry tools = new ToolRegistry()
                // 工具 1：按区县查景点。真实项目里这里换成查 MySQL
                .register(new SimpleTool(
                        "search_spots",
                        "按区县检索乡村文旅景点，返回景点名、等级、门票原价、建议游玩时长",
                        Tool.schema(Map.of("district", Tool.str("区县名称，例如 零陵区")), "district"),
                        a -> {
                            String d = String.valueOf(a.get("district"));
                            if (d == null || d.isBlank()) throw new IllegalArgumentException("district 不能为空");
                            if (!d.contains("零陵")) return "未检索到「" + d + "」的景点数据，请换一个区县";
                            return """
                                    1. 柳子庙 | 4A | 门票 60 元 | 建议 2 小时 | 唐代柳宗元纪念馆
                                    2. 零陵古城 | 4A | 门票 40 元 | 建议 3 小时 | 夜间灯光秀 19:30 开始
                                    3. 周家大院 | 3A | 门票 35 元 | 建议 1.5 小时 | 明清古建筑群
                                    4. 潇水风光带 | 免费 | 建议 1 小时 | 适合傍晚散步
                                    """;
                        }))
                // 工具 2：查天气。真实项目里接第三方天气 API
                .register(new SimpleTool(
                        "get_weather",
                        "查询城市当日天气，返回天气状况与气温区间",
                        Tool.schema(Map.of("city", Tool.str("城市名，例如 永州")), "city"),
                        a -> {
                            String city = String.valueOf(a.get("city"));
                            if (city.contains("永州") || city.contains("零陵")) {
                                return "永州 零陵区：多云转小雨，22~28℃，东南风 2 级，建议带伞";
                            }
                            return "暂无「" + city + "」的天气数据";
                        }))
                // 工具 3：算门票。故意做成工具而不是让模型心算，因为大模型算数并不可靠
                .register(new SimpleTool(
                        "calc_ticket_price",
                        "按原价、人数、折扣计算门票总价，用于给游客报价",
                        Tool.schema(Map.of(
                                "basePrice", Tool.num("单人门票原价，单位元"),
                                "people", Tool.num("人数"),
                                "discount", Tool.num("折扣系数，0.8 表示八折")),
                                "basePrice", "people"),
                        a -> {
                            double base = toDouble(a.get("basePrice"), "basePrice");
                            int people = (int) toDouble(a.get("people"), "people");
                            double discount = a.get("discount") == null ? 1.0 : toDouble(a.get("discount"), "discount");
                            return String.format("门票 = %.0f 元/人 × %d 人 × %.2f 折 = %.2f 元",
                                    base, people, discount, base * people * discount);
                        }));

        // ---------------- 选择模型 ----------------
        // 离线模式用脚本化假模型：预设三步工具调用，用来验证循环是否正确
        // 真实模式交给工厂，由 --provider / 环境变量 / agent.properties 决定用哪家
        LlmClient llm = mock
                ? ScriptedLlmClient.of(
                        List.of(
                                ScriptedLlmClient.call("call_1", "search_spots", Map.of("district", "零陵区")),
                                ScriptedLlmClient.call("call_2", "get_weather", Map.of("city", "永州")),
                                ScriptedLlmClient.call("call_3", "calc_ticket_price",
                                        Map.of("basePrice", 60, "people", 2, "discount", 0.8))),
                        ctx -> "（离线模拟回答）已查景点、天气，并按 8 折算好两个人的门票。\n最后一条工具返回：\n"
                                + ScriptedLlmClient.lastObservation(ctx))
                : LlmFactory.create(args);

        // ---------------- 组装 agent ----------------
        Agent agent = Agent.builder("乡村文旅助手")
                .llm(llm)
                .tools(tools)
                .systemPrompt("""
                        你是「乡村文旅综合服务系统」的行程助手。
                        回答要求：先给结论，再给依据；涉及价格必须调用工具计算，禁止心算。
                        如果工具返回 ERROR，先判断是参数问题还是无数据，再决定重试或告知用户。
                        """)
                .hooks(new LoggingHook())   // 打印每一轮的过程，调试时必开
                .maxSteps(8)
                .build();

        // ---------------- 跑一次任务 ----------------
        String answer = agent.run("帮我安排零陵区一日游，两个人，把门票钱算出来");
        System.out.println("\n================ 最终回答 ================");
        System.out.println(answer);
        System.out.println("=========================================");
    }

    /**
     * --help：打印中文用法说明。
     *
     * 之所以不把这坨文字写在 run.bat 里：cmd.exe 解析含 UTF-8 中文的批处理
     * 存在已知缺陷（中文行后面紧跟的命令有时会被截断），由 Java 输出才稳定。
     */
    private static void printHelp() {
        System.out.println("""
                ===================== mini-agent 用法 =====================

                【离线演示】不联网、不花钱，用来验证内核逻辑
                  run.bat                      主循环：查景点 → 查天气 → 算门票
                  run.bat --search             联网搜索：搜索 → 带 [编号] 引用作答
                  run.bat --code-demo          代码编辑：读文件 → 精确替换 → 校验 → 出差异

                【调试工具】
                  run.bat --dump               打印 OpenAI 与 Anthropic 两种协议报文
                  run.bat --config             只显示生效的模型配置，不发请求
                  run.bat --help               显示本帮助

                【接真实模型】需要先提供密钥
                  run.bat --real                              用 agent.properties 的配置
                  run.bat --real --provider=openai            OpenAI
                  run.bat --real --provider=anthropic         Claude（原生 Messages 协议）
                  run.bat --real --provider=deepseek          DeepSeek
                  run.bat --real --provider=moonshot          Kimi
                  run.bat --real --provider=zhipu             智谱
                  run.bat --real --provider=ollama            本地 Ollama（无需密钥）

                【用中转服务或私有部署】
                  run.bat --real --provider=custom ^
                           --base-url=https://你的地址/v1 --api-key=sk-xxx --model=模型名

                【联网搜索】另需搜索密钥
                  set BOCHA_API_KEY=sk-xxx     博查（国内推荐）
                  set TAVILY_API_KEY=tvly-xxx  Tavily
                  run.bat --search --real

                【代码自动填充与智能修改】
                  run.bat --code "给 total 加儿童半价" --file=samples/TicketService.java --real
                  run.bat --code --real        交互模式，逐行输入指令，exit 退出

                【配置优先级】命令行参数 > 环境变量 > agent.properties > 内置预设
                完整配置模板见：agent.properties.example
                ==========================================================
                """);
    }

    /**
     * 把模型给的参数转成 double。
     * 模型有时会把数字写成字符串（"60"），所以这里不能直接强转，必须兼容字符串。
     *
     * @param v    原始值
     * @param name 参数名，仅用于报错提示
     */
    private static double toDouble(Object v, String name) {
        if (v == null) throw new IllegalArgumentException("缺少必填参数: " + name);
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 必须是数字，实际收到: " + v);
        }
    }

    /** --config：只打印最终生效的配置，不发请求、不花钱，专门用来排查 401 / 404 */
    private static void printConfig(String[] args) {
        LlmFactory.Resolved r = LlmFactory.resolve(args);
        System.out.println("=========== 当前生效的模型配置 ===========");
        System.out.printf("provider  : %s%n", r.provider());
        System.out.printf("endpoint  : %s%n", r.endpoint());
        System.out.printf("model     : %s%n", r.model());
        // key 打码显示，避免截图或录屏时泄露
        System.out.printf("apiKey    : %s%n", LlmFactory.mask(r.apiKey()));
        System.out.printf("protocol  : %s%n", r.anthropic() ? "Anthropic Messages" : "OpenAI 兼容");
        System.out.printf("maxTokens : %d%n", r.maxTokens());
        System.out.println("配置文件   : " + (AgentConfig.hasFile()
                ? "agent.properties 已加载" : "未找到 agent.properties（只用命令行/环境变量/预设）"));
        System.out.println("=========================================");
    }
}
