package mycode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 代码自动填充与智能修改 —— 这个能力的三类触发方式都从这里进：
 *
 *   1. 单次指令（最常用）
 *      run.bat --code "给 total 方法加儿童半价逻辑" --file=samples/TicketService.java
 *   2. 交互模式（连续改一个文件）
 *      run.bat --code
 *   3. 离线演示（不花钱，看工具链怎么跑）
 *      run.bat --code-demo
 *
 * 背后的 agent 只有三个工具：list_files → read_code → edit_code。
 * 没有 read_code 就一定改不对，这是代码类 agent 和聊天类 agent 最大的区别。
 */
public class CodingDemo {

    /** 每次演示前重置，保证可重复运行 */
    private static final String SAMPLE = """
            package samples;

            /**
             * 门票计价示例，供代码编辑能力演示使用。
             */
            public class TicketService {

                /**
                 * 计算门票总价。
                 */
                public double total(double basePrice, int adults, int children) {
                    // 目前只按成人计算，儿童未计入
                    return basePrice * adults;
                }
            }
            """;

    private static final String SAMPLE_PATH = "samples/TicketService.java";

    /**
     * 入口分发：--code-demo 走离线演示，--code 走真实模式（带指令或进交互）。
     *
     * @param args 命令行参数
     */
    public static void run(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("agent.root", ".")).toAbsolutePath().normalize();
        Path sample = root.resolve(SAMPLE_PATH);

        if (List.of(args).contains("--code-demo")) {
            demo(root, sample);
            return;
        }

        String instruction = argValue(args, "--code");
        String file = argValue(args, "--file");

        LlmClient llm = LlmFactory.create(args);
        Agent agent = buildAgent(root, llm);

        if (instruction == null || instruction.isBlank()) {
            repl(agent);
        } else {
            runOnce(agent, instruction, file);
        }
    }

    /**
     * 组装代码编辑 agent：三个工具 + 一份严格的编辑规范。
     *
     * 提示词里那 7 条规则不是装饰，每一条都对应一个真实的失败模式：
     * 不读原文就改 → 锚点对不上；顺手重构 → diff 里混入无关改动；
     * 自己加缩进 → 缩进层级错乱；盲目重试 → 同一处反复报错。
     */
    private static Agent buildAgent(Path root, LlmClient llm) {
        ToolRegistry tools = new ToolRegistry()
                .register(CodeTools.listTool(root))
                .register(CodeTools.readTool(root))
                .register(new CodeEditTool(root));

        return Agent.builder("代码助手")
                .llm(llm)
                .tools(tools)
                .systemPrompt("""
                        你是代码编辑助手，负责按指令精确修改已有代码。
                        工作规则：
                        1. 不知道文件在哪，先 list_files；不确定原文，必须先 read_code。
                        2. 只改指令要求的范围，禁止顺手重构、禁止重写整个文件。
                        3. anchor 必须从 read_code 的结果里逐字复制（含缩进），带足够上下文保证唯一。
                        4. 保持原文件的风格：缩进宽度、注释语言、命名习惯、异常处理方式。
                           原文件用中文注释就写中文注释，用英文就写英文。
                        5. newCode 不要自己加最外层公共缩进，工具会自动对齐到锚点位置。
                        6. 改完在回答里说明：改了哪个方法的第几行、为什么这么改。
                        7. 如果 edit_code 返回 ERROR（锚点不唯一、校验失败等），先读文件再修正，
                           不要盲目重试同一个 anchor。
                        """)
                .hooks(new LoggingHook())
                .maxSteps(10)
                .build();
    }

    /**
     * 跑一条指令并打印结果。
     * 指定了 --file 就把目标文件拼到任务描述里，
     * 这样模型不用先 list_files 也能直接动手，省一轮调用。
     */
    private static void runOnce(Agent agent, String instruction, String file) {
        String task = file == null
                ? instruction
                : instruction + "\n\n目标文件: " + file;
        String answer = agent.run(task);
        System.out.println("\n================ 修改结果 ================");
        System.out.println(answer);
        System.out.println("=========================================");
    }

    /**
     * 交互式修改：一行一条指令，改完立刻能看到差异。
     *
     * 注意每次指令都是独立会话（agent.run 会新建上下文），
     * 所以 agent 每次都要重新 read_code —— 这反而更安全，
     * 避免它拿上一轮的记忆去改已经被改动过的文件。
     */
    private static void repl(Agent agent) throws Exception {
        System.out.println("""
                进入交互式代码修改模式。
                直接输入指令（例如：给 total 加儿童半价），输入 exit 退出。
                注意：每次指令都是独立会话，agent 会重新读取文件。
                """);
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("\n指令> ");
            System.out.flush();
            String line = reader.readLine();
            if (line == null || line.isBlank()) continue;
            if (line.strip().equalsIgnoreCase("exit") || line.strip().equalsIgnoreCase("quit")) break;
            System.out.println(agent.run(line));
        }
    }

    /** 离线演示：用脚本化假模型走一遍「读文件 → 精确替换 → 校验 → 输出差异」 */
    private static void demo(Path root, Path sample) throws Exception {
        Files.createDirectories(sample.getParent());
        Files.writeString(sample, SAMPLE, StandardCharsets.UTF_8);
        System.out.println("已重置演示文件: " + SAMPLE_PATH + "\n");

        LlmClient llm = ScriptedLlmClient.of(
                List.of(
                        ScriptedLlmClient.call("call_1", "read_code", Map.of("path", SAMPLE_PATH)),
                        ScriptedLlmClient.call("call_2", "edit_code", Map.of(
                                "path", SAMPLE_PATH,
                                "op", "replace",
                                "anchor", "        // 目前只按成人计算，儿童未计入\n        return basePrice * adults;",
                                "newCode", "        // 儿童按成人价的一半计入\n"
                                        + "        double childPrice = basePrice * 0.5;\n"
                                        + "        return basePrice * adults + childPrice * children;"))),
                ctx -> "已修改 TicketService.total 方法：儿童按成人价的一半计入，"
                        + "保持原有的中文注释与 4 空格缩进风格。");

        Agent agent = buildAgent(root, llm);
        runOnce(agent, "让 total 方法支持儿童半价，保持现有代码风格", SAMPLE_PATH);
    }

    /** 从 --name=value 形式的参数里取值，取不到返回 null */
    private static String argValue(String[] args, String name) {
        for (String a : args) {
            if (a.startsWith(name + "=")) {
                String v = a.substring(name.length() + 1).strip();
                if (!v.isEmpty()) return v;
            }
        }
        return null;
    }

    /** 演示类，禁止实例化 */
    private CodingDemo() {
    }
}
