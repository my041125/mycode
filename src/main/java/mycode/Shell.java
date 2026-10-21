package mycode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * mycode 的命令行界面（REPL）。
 *
 * 交互形态参考 Claude Code：启动后进入对话，直接说话，agent 自己决定要不要调工具。
 * 斜杠命令负责本地操作（清屏、看工具、退出），普通文字交给模型。
 *
 * 三种启动方式：
 *   mycode                     进入交互模式
 *   mycode "帮我改一下 xxx"      单次执行，跑完就退出（适合脚本调用）
 *   mycode --demo              离线演示：用脚本化假模型，不联网不花钱
 *
 * 为什么不用 JLine/Spring Shell：那类库会引入一堆依赖，
 * 而这里只需要「读一行、打印一行」，JDK 自带的 BufferedReader 足够。
 */
public class Shell {

    /** 版本号，改功能时顺手改一下，方便确认跑的是哪个版本 */
    private static final String VERSION = "1.0";

    /** 提示符 */
    private static final String PROMPT = "\n› ";

    /**
     * 系统提示词。命令行编程助手的行为规范都在这里。
     * 每一条都对应一个真实的失败模式，不是凑字数。
     */
    private static final String SYSTEM_PROMPT = """
            你是 mycode，一个运行在终端里的编程助手，工作目录就是用户当前所在的项目。

            工作方式：
            1. 需要了解代码时，先用 list_files 定位文件，再用 read_code 读原文，不要靠猜；
            2. 改代码必须用 edit_code，并且 anchor 从 read_code 的结果里逐字复制；
            3. 一次只改指令要求的范围，禁止顺手重构、禁止重写整个文件；
            4. 改动后 edit_code 会自动做语法校验，如果返回 ERROR 就先读文件再修正，不要盲目重试；
            5. 保持原文件风格：缩进宽度、注释语言、命名习惯都跟原文件走；
            6. 回答用中文，先给结论再给依据，代码位置要具体到文件和方法名；
            7. 没把握的事直说，不要编造文件内容或 API。
            """;

    /**
     * 交互模式主流程。
     *
     * @param args 命令行参数，支持 --demo（离线演示）
     */
    public static void run(String[] args) throws Exception {
        boolean demo = List.of(args).contains("--demo");
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

        ToolRegistry tools = buildTools(root);
        LlmClient llm = demo ? demoModel() : LlmFactory.create(args);

        Agent agent = Agent.builder("mycode")
                .llm(llm)
                .tools(tools)
                .systemPrompt(SYSTEM_PROMPT)
                .hooks(new ConsoleHook())
                .maxSteps(12)
                .build();

        printBanner(demo, args, tools, root);
        ChatSession session = new ChatSession(agent);
        repl(session, tools, root);
    }

    /**
     * 单次执行模式：我的命令行里直接 mycode "问题" 就走这里。
     * 跑完立刻退出，输出可以直接重定向到文件，方便当脚本用。
     *
     * @param question 用户问题
     * @param args     其余参数，用于选择模型
     */
    public static void runOnce(String question, String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        ToolRegistry tools = buildTools(root);
        Agent agent = Agent.builder("mycode")
                .llm(LlmFactory.create(args))
                .tools(tools)
                .systemPrompt(SYSTEM_PROMPT)
                .hooks(new ConsoleHook())
                .maxSteps(12)
                .build();

        ChatSession session = new ChatSession(agent);
        long start = System.currentTimeMillis();
        System.out.println(session.ask(question));
        System.out.printf("%n— 用时 %.1fs · 上下文约 %d 字%n",
                (System.currentTimeMillis() - start) / 1000.0, session.contextChars());
    }

    // ---------------- 交互循环 ----------------

    /**
     * 读一行 → 处理 → 打印 → 再读一行。
     * 支持 Ctrl+D（EOF）退出，也支持 /exit /quit。
     */
    private static void repl(ChatSession session, ToolRegistry tools, Path root) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));

        while (true) {
            System.out.print(PROMPT);
            System.out.flush();

            String line = reader.readLine();
            if (line == null) {                 // Ctrl+D / 管道结束
                System.out.println("\n再见。");
                return;
            }
            String input = line.strip();
            if (input.isEmpty()) continue;

            // 敲完回车先换行，工具输出才不会顶在提示符后面
            System.out.println();

            // 斜杠命令在本地处理，不进模型，省 token 也更可靠
            if (input.startsWith("/")) {
                if (!handleCommand(input, session, tools, root)) return;
                continue;
            }

            long start = System.currentTimeMillis();
            try {
                String answer = session.ask(input);
                System.out.println("\n" + answer);
                System.out.printf("%n— 用时 %.1fs · 上下文约 %d 字 · 第 %d 轮%n",
                        (System.currentTimeMillis() - start) / 1000.0,
                        session.contextChars(), session.turns());
            } catch (Exception e) {
                // 单次失败不退出会话，用户可以直接重试或换个说法
                System.out.println("\n[错误] " + e.getMessage());
            }
        }
    }

    /**
     * 处理斜杠命令。
     *
     * @return false 表示要退出会话
     */
    private static boolean handleCommand(String input, ChatSession session,
                                         ToolRegistry tools, Path root) {
        String cmd = input.split("\\s+")[0].toLowerCase();
        switch (cmd) {
            case "/exit", "/quit", "/q" -> {
                System.out.println("再见。本次共 " + session.turns() + " 轮，"
                        + session.elapsedSeconds() + " 秒。");
                return false;
            }
            case "/help" -> printHelp();
            case "/clear" -> {
                session.reset();
                System.out.println("已清空上下文，开始新会话。");
            }
            case "/tools" -> {
                System.out.println("已加载 " + tools.all().size() + " 个工具：");
                for (Tool t : tools.all()) {
                    // 描述可能很长，只取第一句，保持列表清爽
                    String desc = t.description().split("[。\n]")[0];
                    System.out.printf("  %-14s %s%n", t.name(), desc);
                }
            }
            case "/files" -> listFiles(root);
            case "/model" -> printModel();
            case "/cost" -> System.out.printf("上下文约 %d 字，已完成 %d 轮，耗时 %d 秒。%n",
                    session.contextChars(), session.turns(), session.elapsedSeconds());
            default -> System.out.println("未知命令: " + cmd + "，输入 /help 查看可用命令。");
        }
        return true;
    }

    /** 列出当前项目里的代码文件，最多 30 个，避免刷屏 */
    private static void listFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root, 8)) {
            List<String> files = walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String s = p.toString().replace('\\', '/');
                        return !s.contains("/target/") && !s.contains("/.git/")
                                && !s.endsWith(".bak") && !s.endsWith(".jar");
                    })
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    // 先截断再排序：否则 sorted() 会把整个目录树走完，
                    // 在盘符根目录下执行会卡好几秒
                    .limit(200)
                    .sorted()
                    .limit(30)
                    .toList();
            System.out.println("当前目录下 " + files.size() + " 个文件：");
            files.forEach(f -> System.out.println("  " + f));
        } catch (Exception e) {
            System.out.println("列目录失败: " + e.getMessage());
        }
    }

    /** 展示当前生效的模型配置，key 打码 */
    private static void printModel() {
        try {
            LlmFactory.Resolved r = LlmFactory.resolve(new String[0]);
            System.out.printf("provider : %s%nendpoint : %s%nmodel    : %s%napiKey   : %s%n协议     : %s%n",
                    r.provider(), r.endpoint(), r.model(), LlmFactory.mask(r.apiKey()),
                    r.anthropic() ? "Anthropic Messages" : "OpenAI 兼容");
        } catch (Exception e) {
            System.out.println("配置有误: " + e.getMessage());
        }
    }

    private static void printHelp() {
        System.out.println("""
                用法：直接输入问题，回车发送。需要 agent 动手时会自动调用工具。

                斜杠命令（本地执行，不消耗 token）：
                  /help      显示本帮助
                  /clear     清空上下文，开始新会话
                  /tools     查看已加载的工具
                  /files     列出当前项目的代码文件
                  /model     查看当前模型配置
                  /cost      查看上下文长度与耗时
                  /exit      退出（同 Ctrl+D）

                示例：
                  › 看看 src/main/java/mycode/Agent.java 里的循环有没问题
                  › 给 TicketService 的 total 方法加儿童半价，别动注释风格
                  › 帮我找一下哪个文件定义了 edit_code 工具
                """);
    }

    // ---------------- 启动横幅 ----------------

    private static void printBanner(boolean demo, String[] args, ToolRegistry tools, Path root) {
        String model;
        if (demo) {
            model = "离线演示模型（脚本化，不联网）";
        } else {
            try {
                LlmFactory.Resolved r = LlmFactory.resolve(args);
                model = r.provider() + " / " + r.model();
                // 模板里的占位符很容易被忽略，这里提前提醒，省得用户第一句话就吃到 401
                String key = r.apiKey();
                if (key == null || key.isBlank() || key.contains("你的")) {
                    model = model + "   ⚠ 还没填 API Key（编辑 agent.properties）";
                }
            } catch (Exception e) {
                model = "未配置（先用 /model 检查）";
            }
        }

        System.out.println();
        System.out.println("  mycode v" + VERSION + "  ·  手写 agent 内核的命令行助手");
        System.out.println("  " + "-".repeat(52));
        System.out.println("  模型   " + model);
        System.out.println("  工具   " + tools.all().size() + " 个");
        System.out.println("  目录   " + root);
        System.out.println("  " + "-".repeat(52));
        System.out.println("  直接说话即可，/help 看命令，/exit 退出");
    }

    // ---------------- 装配 ----------------

    /**
     * 组装工具：代码三件套 + 可选的联网搜索。
     *
     * 搜索工具只在配了密钥时才挂上 —— 命令行工具不该因为缺一个可选功能就启动失败。
     */
    private static ToolRegistry buildTools(Path root) {
        ToolRegistry tools = new ToolRegistry()
                .register(CodeTools.listTool(root))
                .register(CodeTools.readTool(root))
                .register(new CodeEditTool(root));

        String bocha = System.getenv("BOCHA_API_KEY");
        String tavily = System.getenv("TAVILY_API_KEY");
        if (bocha != null && !bocha.isBlank()) {
            tools.register(new WebSearchTool(new BochaSearchProvider(bocha), 3))
                    .register(new FetchPageTool());
        } else if (tavily != null && !tavily.isBlank()) {
            tools.register(new WebSearchTool(new TavilySearchProvider(tavily), 3))
                    .register(new FetchPageTool());
        }
        return tools;
    }

    /**
     * 离线演示用的假模型：先列目录、再读一个文件，然后给结论。
     * 用来在没有 API key 的情况下验证整条交互链路。
     */
    private static LlmClient demoModel() {
        return ScriptedLlmClient.of(
                List.of(
                        ScriptedLlmClient.call("call_1", "list_files",
                                Map.of("dir", "src/main/java/mycode", "keyword", "Agent")),
                        ScriptedLlmClient.call("call_2", "read_code",
                                Map.of("path", "samples/TicketService.java"))),
                ctx -> """
                        这是离线演示模式的回答：已经列出目录并读过一个文件，但不会真的调用模型。

                        要体验真实效果，请在 agent.properties 里填好 apiKey，然后运行：
                          mycode

                        下面是你刚才实际执行的工具返回，可以用它确认链路是否正常：
                        """ + "\n" + ScriptedLlmClient.lastObservation(ctx));
    }

    /** 工具类，禁止实例化 */
    private Shell() {
    }
}
