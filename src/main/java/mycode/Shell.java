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
 * 权限由 --permission 控制（read-only / edit / full），默认 edit：
 * 改文件放行，执行命令逐条确认。
 */
public class Shell {

    /** 版本号，改功能时顺手改一下，方便确认跑的是哪个版本 */
    private static final String VERSION = "1.2";

    /** 提示符 */
    private static final String PROMPT = "\n› ";

    /**
     * 启动参数。
     * /model 需要按真实参数解析，否则会把 agent.properties 的配置当成生效配置显示。
     */
    private static String[] startupArgs = new String[0];

    /** 当前界面/回答语言，默认简体中文，可用 --lang=en 或 /lang en 切换 */
    private static Language lang = Language.ZH;

    /**
     * 运行期依赖。/lang 切换语言时要重建 agent，所以这些引用得留着。
     */
    private record Runtime(LlmClient llm, ToolRegistry tools, PermissionMode mode, BufferedReader stdin) {
    }

    /**
     * 会话容器。
     * 用可变容器而不是直接传 ChatSession，是因为 /lang 会整只替换掉会话。
     */
    private static final class SessionHolder {
        private ChatSession session;

        private SessionHolder(ChatSession session) {
            this.session = session;
        }
    }

    /**
     * 系统提示词。命令行编程助手的行为规范都在这里。
     * 每一条都对应一个真实的失败模式，不是凑字数。
     */
    private static final String SYSTEM_PROMPT = """
            你是 mycode，一个运行在终端里的编程助手，工作目录就是用户当前所在的项目。

            工作方式：
            1. 需要了解代码时，先用 list_files 定位文件，再用 read_code 读原文，不要靠猜；
            2. 改代码必须用 edit_code，并且 anchor 从 read_code 的结果里逐字复制；
            3. 改完要用 run_command 验证（例如 mvn -q compile），根据报错继续修，直到通过；
            4. 一次只改指令要求的范围，禁止顺手重构、禁止重写整个文件；
            5. edit_code 会自动做语法校验，如果返回 ERROR 就先读文件再修正，不要盲目重试；
            6. 保持原文件风格：缩进宽度、注释语言、命名习惯都跟原文件走；
            7. 先给结论再给依据，代码位置要具体到文件和方法名（回答语言见文末的语言要求）；
            8. 没把握的事直说，不要编造文件内容或 API。
            """;

    /**
     * 交互模式主流程。
     *
     * @param args 命令行参数，支持 --demo / --permission=xxx
     */
    public static void run(String[] args) throws Exception {
        startupArgs = args;
        lang = Language.fromArgs(args);
        boolean demo = List.of(args).contains("--demo");
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

        ToolRegistry tools = buildTools(root);
        LlmClient llm = demo ? demoModel() : LlmFactory.create(args);
        PermissionMode mode = PermissionMode.parse(argValue(args, "--permission"));

        // 整个会话共用同一个输入流：REPL 读指令，权限确认也读它，
        // 分开建会导致缓冲区里的内容错乱
        BufferedReader stdin = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));

        // 只有真实终端才把输入流交给确认器：
        // 管道/重定向场景下 stdin 里是脚本内容，确认器一读就会把下一条指令吃掉，
        // 所以此时传 null，让 PermissionHook 走「非交互一律拒绝」的分支
        boolean tty = System.console() != null;
        Runtime rt = new Runtime(llm, tools, mode, tty ? stdin : null);
        lastRuntime = rt;   // /lang 重建 agent 时要用

        SessionHolder holder = new SessionHolder(new ChatSession(buildAgent(rt)));
        printBanner(demo, args, tools, root, mode);
        repl(holder, rt, root, stdin);
    }

    /**
     * 单次执行模式：mycode "问题" 走这里。
     * 跑完立刻退出，输出可以直接重定向到文件，方便当脚本用。
     *
     * 没有交互环境，所以权限确认一律按拒绝处理（只读命令不受影响）。
     *
     * @param question 用户问题
     * @param args     其余参数，用于选择模型与权限
     */
    public static void runOnce(String question, String[] args) throws Exception {
        startupArgs = args;
        lang = Language.fromArgs(args);
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        ToolRegistry tools = buildTools(root);
        PermissionMode mode = PermissionMode.parse(argValue(args, "--permission"));
        Runtime rt = new Runtime(LlmFactory.create(args), tools, mode, null);
        lastRuntime = rt;

        ChatSession session = new ChatSession(buildAgent(rt));
        long start = System.currentTimeMillis();

        // 单次模式也开流式：边出边打印，观感好，也方便管道实时看到输出
        StringBuilder streamed = new StringBuilder();
        String answer = session.ask(question, delta -> {
            streamed.append(delta);
            System.out.print(delta);
            System.out.flush();
        });
        if (streamed.length() == 0) {
            System.out.println(answer);   // 没走流式（或模型没吐文字）时整段打印
        }
        System.out.printf("%n— 用时 %.1fs · %s · %s%n",
                (System.currentTimeMillis() - start) / 1000.0,
                Costs.describe(session.totalUsage()),
                "上下文约 " + session.contextChars() + " 字");
    }

    // ---------------- 交互循环 ----------------

    /**
     * 读一行 → 处理 → 打印 → 再读一行。
     * 支持 Ctrl+D（EOF）退出，也支持 /exit /quit。
     */
    private static void repl(SessionHolder holder, Runtime rt, Path root,
                             BufferedReader reader) throws Exception {
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
                if (!handleCommand(input, holder, rt.tools(), root, rt.mode())) return;
                continue;
            }

            ChatSession session = holder.session;
            long start = System.currentTimeMillis();
            try {
                // 流式：文字边生成边打印；没有文字增量时（例如纯工具调用）就什么都不打
                StringBuilder streamed = new StringBuilder();
                String answer = session.ask(input, delta -> {
                    streamed.append(delta);
                    System.out.print(delta);
                    System.out.flush();
                });
                if (streamed.length() == 0) {
                    System.out.println(answer);
                } else if (!answer.equals(streamed.toString()) && !answer.isBlank()) {
                    // 极少数情况：流式拼接的文本与最终文本不一致，以最终文本为准补一行提示
                    System.out.println();
                }
                System.out.printf("%n— 用时 %.1fs · %s · 上下文约 %d 字 · 第 %d 轮%n",
                        (System.currentTimeMillis() - start) / 1000.0,
                        Costs.describe(session.totalUsage()),
                        session.contextChars(), session.turns());
            } catch (Exception e) {
                // 单次失败不退出会话，用户可以直接重试或换个说法
                System.out.println("\n[错误] " + friendlyError(e));
            }
        }
    }

    /**
     * 处理斜杠命令。
     *
     * @return false 表示要退出会话
     */
    private static boolean handleCommand(String input, SessionHolder holder,
                                         ToolRegistry tools, Path root, PermissionMode mode) {
        String cmd = input.split("\\s+")[0].toLowerCase();
        ChatSession session = holder.session;
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
            case "/lang" -> switchLanguage(input, holder);
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
            case "/permission" -> System.out.printf("当前权限模式：%s（%s）%n", mode.cli, mode.label);
            case "/cost" -> printCost(session);
            case "/compress" -> compress(session);
            case "/undo" -> undo(root, input);
            case "/history" -> history(root);
            default -> System.out.println("未知命令: " + cmd + "，输入 /help 查看可用命令。");
        }
        return true;
    }

    /**
     * /lang：查看或切换回答语言。
     *
     * 切换会重建 agent —— 语言约束写在 system 提示词里，换了提示词就必须新开会话，
     * 否则旧上下文是用另一种语言表述的，摘要和记忆会对不上。
     */
    private static void switchLanguage(String input, SessionHolder holder) {
        String arg = input.length() > "/lang".length() ? input.substring("/lang".length()).strip() : "";
        if (arg.isEmpty()) {
            System.out.printf("当前语言：%s（%s）%n", lang.code, lang.label);
            System.out.println("用法：/lang zh 切换中文，/lang en 切换英文");
            return;
        }
        Language next = Language.parse(arg);
        if (next == lang) {
            System.out.println("已经是 " + next.label + " 了。");
            return;
        }
        lang = next;
        holder.session = new ChatSession(buildAgent(currentRuntime()));
        System.out.printf("已切换到 %s。因为语言约束写在系统提示词里，上下文已清空、重新开始。%n", next.label);
    }

    /** 保存最近一次的运行期依赖，供 /lang 重建 agent 用 */
    private static Runtime lastRuntime;

    private static Runtime currentRuntime() {
        return lastRuntime;
    }

    /**
     * /undo：把文件退回到上一个版本。
     *
     * 不带参数时恢复「全局最近一次被修改的文件」，
     * 带路径时恢复指定文件的最新快照。
     */
    private static void undo(Path root, String input) {
        String arg = input.length() > "/undo".length() ? input.substring("/undo".length()).strip() : "";
        try {
            BackupStore store = new BackupStore(root);
            Path target = arg.isEmpty() ? null : root.resolve(arg).normalize();
            Path restored = store.restore(target);
            System.out.println("已恢复到上一个版本: " + rel(root, restored));
        } catch (IllegalStateException e) {
            System.out.println("没有可恢复的历史版本（还没改过文件，或快照已用完）。");
        } catch (Exception e) {
            System.out.println("恢复失败: " + e.getMessage());
        }
    }

    /** /history：列出最近几次修改前的快照 */
    private static void history(Path root) {
        try {
            List<BackupStore.Snapshot> list = new BackupStore(root).recent(8);
            if (list.isEmpty()) {
                System.out.println("还没有历史版本。改过文件之后这里会有记录。");
                return;
            }
            System.out.println("最近 " + list.size() + " 次修改前的快照：");
            for (BackupStore.Snapshot s : list) {
                System.out.printf("  %s  %-38s %,d 字节%n",
                        prettyStamp(s.stamp()), rel(root, s.file()), s.sizeBytes());
            }
            System.out.println("提示：/undo 恢复最近一次；/undo 路径 恢复指定文件");
        } catch (Exception e) {
            System.out.println("读取历史失败: " + e.getMessage());
        }
    }

    /** 相对路径展示，统一用正斜杠 */
    private static String rel(Path root, Path file) {
        try {
            return root.relativize(file).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    /** 把 20261021-165812-123 变成 2026-10-21 16:58:12 */
    private static String prettyStamp(String stamp) {
        if (stamp.length() < 15) return stamp;
        return stamp.substring(0, 4) + "-" + stamp.substring(4, 6) + "-" + stamp.substring(6, 8)
                + " " + stamp.substring(9, 11) + ":" + stamp.substring(11, 13) + ":" + stamp.substring(13, 15);
    }

    /**
     * /compress：手动压缩一次上下文。
     *
     * 自动压缩在每轮开始前按阈值触发，这个命令用于「还没到阈值但我想提前瘦身」，
     * 也方便观察压缩到底做了什么。
     */
    private static void compress(ChatSession session) {
        int before = session.contextChars();
        try {
            ContextCompressor.Stats stats = session.compressNow();
            if (stats.removedMessages() == 0) {
                System.out.printf("当前上下文约 %d 字，还没到需要压缩的程度（阈值 %d 字）。%n",
                        before, session.compressThreshold());
                return;
            }
            System.out.printf("已压缩：移除 %d 条消息（%d 轮），约 %d 字 → %d 字%n",
                    stats.removedMessages(), stats.removedRounds(), before, session.contextChars());
            System.out.println("摘要方式：" + (stats.usedModel() ? "模型生成" : "机械兜底（模型摘要不可用）"));
            System.out.println("新的历史摘要：");
            System.out.println("  " + session.summary().replace("\n", "\n  "));
        } catch (Exception e) {
            System.out.println("压缩失败: " + e.getMessage());
        }
    }

    /** 用量与耗时汇总。配了价格才显示金额，否则只报 token */
    private static void printCost(ChatSession session) {        System.out.println("模型用量 : " + Costs.describe(session.totalUsage()));
        System.out.println("上下文   : 约 " + session.contextChars() + " 字");
        System.out.println("轮次     : " + session.turns());
        System.out.println("耗时     : " + session.elapsedSeconds() + " 秒");
        if (!Costs.configured()) {
            System.out.println("提示     : 在 agent.properties 里配置 priceInputPerK / priceOutputPerK 可显示金额");
        }
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

    /** 展示当前生效的模型配置，key 打码。字段名也用中文，避免界面中英混杂 */
    private static void printModel() {
        try {
            // 必须按启动参数解析：否则 mycode --provider=openai 进来后
            // /model 显示的却是 agent.properties 里的 dashscope，误导排查
            LlmFactory.Resolved r = LlmFactory.resolve(startupArgs);
            System.out.printf("服务商 : %s%n接口地址: %s%n模型   : %s%n密钥   : %s%n协议   : %s%n语言   : %s%n",
                    r.provider(), r.endpoint(), r.model(), LlmFactory.mask(r.apiKey()),
                    r.anthropic() ? "Anthropic Messages" : "OpenAI 兼容",
                    lang.label);
        } catch (Exception e) {
            System.out.println("配置有误：" + friendlyError(e));
        }
    }

    /**
     * 把英文异常翻成人话。
     *
     * 客户端是中文的，但底层异常信息基本都是英文（HTTP 401、Connection refused…），
     * 直接抛给用户等于没提示。这里按常见故障给一句中文解释，再附上原始信息便于排查。
     */
    private static String friendlyError(Exception e) {
        String raw = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lower = raw.toLowerCase();
        if (raw.contains("401") || lower.contains("unauthorized") || lower.contains("invalid api key")) {
            return "模型服务拒绝了请求（401），请检查 agent.properties 里的 apiKey 是否填对。原始信息：" + raw;
        }
        if (raw.contains("404") || lower.contains("not found")) {
            return "接口地址不存在（404），请用 /model 确认 endpoint 是否正确。原始信息：" + raw;
        }
        if (raw.contains("429") || lower.contains("rate limit")) {
            return "请求太频繁或额度用尽（429），稍等一会儿再试。原始信息：" + raw;
        }
        if (raw.contains("403") || lower.contains("forbidden")) {
            return "没有访问权限（403），确认这个 key 是否开通了该模型。原始信息：" + raw;
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "连接超时，检查网络或代理设置。原始信息：" + raw;
        }
        if (lower.contains("unknownhost") || lower.contains("connection refused")) {
            return "连不上服务器，检查网络或 baseUrl。原始信息：" + raw;
        }
        return raw;
    }

    private static void printHelp() {
        System.out.println("""
                用法：直接输入问题，回车发送。需要它动手时会自动调用工具。

                斜杠命令（本地执行，不消耗 token）：
                  /help        显示本帮助
                  /clear       清空上下文，开始新会话
                  /tools       查看已加载的工具
                  /files       列出当前项目的代码文件
                  /model       查看当前模型配置
                  /permission  查看当前权限模式
                  /lang        查看或切换回答语言（/lang zh | /lang en）
                  /cost        查看 token 用量与耗时
                  /compress    手动压缩一次上下文（自动压缩默认已开启）
                  /undo        撤销最近一次文件修改（/undo 路径 指定文件）
                  /history     查看最近几次修改前的快照
                  /exit        退出（同 Ctrl+D）

                快照说明：
                  每次改文件前都会在 .mycode/backups/ 下存一份，每个文件保留最近 20 个版本，
                  所以连改多次也能一层层退回去。语法校验失败的修改会被自动回滚，不占版本。

                记忆压缩说明：
                  上下文超过阈值（默认 70%）时，最老的几轮对话会被压成一段摘要放进系统提示词，
                  而不是直接删掉 —— 这样任务意图和已确认的约束能留下来。
                  压缩发生在后台，界面会提示「⇣ 上下文已压缩」。用 --no-compress 可关闭。

                权限模式（启动参数 --permission=）：
                  read-only    只读，不能改文件也不能执行命令
                  edit         可改文件；执行命令需要你确认（默认）
                  full         全部放行，不再询问

                安全的只读命令（ls/git status/java -version 等）不会打断你；
                危险命令（删除、格式化、强制推送）永远拒绝。

                示例：
                  › 看看 src/main/java/mycode/Agent.java 里的循环有没问题
                  › 给 TicketService 的 total 方法加儿童半价，然后编译验证
                  › 帮我跑一下测试，看看哪里挂了
                """);
    }

    // ---------------- 启动横幅 ----------------

    private static void printBanner(boolean demo, String[] args, ToolRegistry tools,
                                    Path root, PermissionMode mode) {
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
        System.out.println("  权限   " + mode.cli + "（" + mode.label + "）");
        System.out.println("  语言   " + lang.label + "（/lang 可切换）");
        System.out.println("  目录   " + root);
        System.out.println("  " + "-".repeat(52));
        System.out.println("  直接说话即可，/help 看命令，/exit 退出");
    }

    // ---------------- 装配 ----------------

    /**
     * 组装 agent：模型 + 工具 + 两个钩子。
     *
     * 钩子顺序有讲究：ConsoleHook 先打「它要调用什么」，PermissionHook 再决定放不放行，
     * 这样用户在被询问之前就知道这行命令是什么来路。
     */
    /**
     * 组装 agent：模型 + 工具 + 两个钩子 + 语言约束。
     *
     * 钩子顺序有讲究：ConsoleHook 先打「它要调用什么」，PermissionHook 再决定放不放行，
     * 这样用户在被询问之前就知道这行命令是什么来路。
     *
     * 语言约束是拼在 system 末尾的：它在提示词里优先级最高，
     * 才能压住「工具返回英文就跟着说英文」的惯性。
     */
    private static Agent buildAgent(Runtime rt) {
        return Agent.builder("mycode")
                .llm(rt.llm())
                .tools(rt.tools())
                .systemPrompt(SYSTEM_PROMPT + "\n" + lang.hint)
                .hooks(new ConsoleHook(), PermissionHook.forMode(rt.mode(), rt.stdin()))
                .maxSteps(12)
                // --no-compress 关掉自动压缩，退回「删最老消息」的旧策略（用于对比效果）
                .compressionEnabled(!List.of(startupArgs).contains("--no-compress"))
                .build();
    }

    /**
     * 组装工具：代码三件套 + 命令执行 + 可选的联网搜索。
     *
     * 搜索工具只在配了密钥时才挂上 —— 命令行工具不该因为缺一个可选功能就启动失败。
     */
    private static ToolRegistry buildTools(Path root) {
        ToolRegistry tools = new ToolRegistry()
                .register(CodeTools.listTool(root))
                .register(CodeTools.readTool(root))
                .register(new CodeEditTool(root))
                .register(new CommandTool(root));   // 编译、跑测试，闭环的关键

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
     * 离线演示用的假模型：列目录 → 读文件 → 跑一条只读命令，然后给结论。
     * 用来在没有 API key 的情况下验证整条交互链路（包括权限与流式）。
     */
    private static LlmClient demoModel() {
        return ScriptedLlmClient.of(
                List.of(
                        ScriptedLlmClient.call("call_1", "list_files",
                                Map.of("dir", "src/main/java/mycode", "keyword", "Agent")),
                        ScriptedLlmClient.call("call_2", "read_code",
                                Map.of("path", "samples/TicketService.java")),
                        ScriptedLlmClient.call("call_3", "run_command",
                                Map.of("command", "java -version"))),
                ctx -> """
                        这是离线演示模式的回答：列出了目录、读过一个文件，并尝试执行了一条只读命令，
                        但不会真的调用模型。

                        要体验真实效果，请在 agent.properties 里填好 apiKey，然后运行：
                          mycode

                        下面是你刚才实际执行的最后一条工具返回，可以用它确认链路是否正常：
                        """ + "\n" + ScriptedLlmClient.lastObservation(ctx));
    }

    /**
     * 从 --name=value 形式的参数里取值。
     *
     * @return 取不到返回 null
     */
    private static String argValue(String[] args, String name) {
        for (String a : args) {
            if (a.startsWith(name + "=")) {
                String v = a.substring(name.length() + 1).strip();
                if (!v.isEmpty()) return v;
            }
        }
        return null;
    }

    /** 工具类，禁止实例化 */
    private Shell() {
    }
}
