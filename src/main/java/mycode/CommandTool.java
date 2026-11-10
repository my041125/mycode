package mycode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 命令执行工具 —— 补上编码 agent 最关键的那块拼图。
 *
 * 没有它，agent 只能「读代码、改代码」，改完相当于闭着眼睛；
 * 有了它才能形成真正的闭环：
 *   改代码 → mvn compile 看报错 → 根据报错再改 → 再编译 → 通过
 *
 * 三道保险，缺一不可：
 *   1. CommandPolicy 判定危险命令，直接拒绝
 *   2. PermissionHook 在 EDIT 模式下要求人工确认（本类只管执行，不管审批）
 *   3. 超时强杀 + 输出截断，防止一条卡死的命令把整个会话拖死
 * @author my
 */
public class CommandTool implements Tool {

    /** 允许的最长执行时间，超过就强杀 */
    private static final int MAX_TIMEOUT_SECONDS = 120;

    /** 默认超时 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** 输出上限，超出部分丢弃（模型看最后一段最有用，但保留开头才能看到错误起点） */
    private static final int MAX_OUTPUT_CHARS = 6000;

    /** 执行命令的工作目录 */
    private final Path workDir;

    /** 一个后台任务的登记信息 */
    record BackgroundTask(String id, Process process, Path logFile, long startedAtMillis) {
    }

    /** 后台任务登记表。TaskOutputTool 查询时要用，包内可见 */
    private static final Map<String, BackgroundTask> BACKGROUND = new ConcurrentHashMap<>();

    /** 后台任务 id 发号器：b1、b2、b3… */
    private static final AtomicInteger NEXT_BACKGROUND_ID = new AtomicInteger(1);

    public CommandTool(Path workDir) {
        this.workDir = workDir.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "run_command";
    }

    @Override
    public String description() {
        return """
                在项目目录下执行一条 shell 命令，用于编译、跑测试、查看版本等。
                使用要求：
                1. 改完代码后应该主动用它验证（例如 mvn compile、mvn test），不要假设改对了；
                2. 一次只跑一条命令，不要用 && 串一长串；
                3. 危险命令（删除、格式化、强制推送等）会被直接拒绝，不要尝试绕过；
                4. 命令默认 30 秒超时，安装依赖这类耗时操作请显式传 timeoutSeconds；
                5. 项目根目录如果有 .venv，python 会自动指向其中的解释器（技能依赖装在那里），
                   所以直接写 python xxx.py 就行，不用拼绝对路径；
                6. 编译、测试、起服务这类长命令传 background=true 放到后台跑，会立即返回任务 id，
                   之后用 task_output 查询状态和输出，不要让会话干等。
                """;
    }

    @Override
    public Map<String, Object> parameters() {
        return Tool.schema(Map.of(
                        "command", Tool.str("要执行的命令，例如 mvn -q compile"),
                        "timeoutSeconds", Tool.num("超时秒数，默认 30，最大 120（仅前台模式生效）"),
                        "background", Tool.str("true 表示后台执行，立即返回任务 id；默认前台")),
                "command");
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String command = args.get("command") == null ? "" : String.valueOf(args.get("command")).strip();

        // 第一道保险：危险命令在这里就挡住，不依赖调用方记得先问策略
        CommandPolicy.Decision decision = CommandPolicy.evaluate(command);
        if (!decision.allowed()) {
            throw new IllegalArgumentException(decision.reason());
        }

        int timeout = Math.max(1, Math.min(MAX_TIMEOUT_SECONDS,
                Tool.toInt(args.get("timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS)));
        boolean background = "true".equalsIgnoreCase(
                String.valueOf(args.getOrDefault("background", "false")));

        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmdline = windows
                ? List.of("cmd.exe", "/c", command)
                : List.of("sh", "-c", command);

        long start = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(cmdline)
                .directory(workDir.toFile())
                .redirectErrorStream(true);   // stderr 合并进 stdout，模型不用分开看

        // 把项目内的「自带工具」前置到 PATH。
        // 两类东西需要它：.venv 里的 python（技能依赖装在这），
        // 以及 tools/ 下的便携软件（例如 LibreOffice 的 soffice，技能按 PATH 找它）。
        // 这样做的好处是整套依赖跟着项目走，既不用管理员权限，也不污染系统环境。
        String extraPath = buildToolPath(windows);
        if (!extraPath.isEmpty()) {
            String path = pb.environment().getOrDefault("PATH", "");
            pb.environment().put("PATH", extraPath + File.pathSeparator + path);
        }
        // Python 默认按系统编码输出，Windows 下是 GBK，中文会乱码
        pb.environment().put("PYTHONIOENCODING", "utf-8");

        // 后台分支：命令已过策略检查、环境已就绪，只差「不等待、输出进日志」
        if (background) {
            return launchBackground(command, pb);
        }

        Process process = pb.start();

        // 用两个线程分别读流和等待，避免「进程写满缓冲区阻塞」的经典死锁。
        //
        // 这里有一个容易忽略的并发问题：reader 线程往 output 里写，主线程在超时路径上
        // 可能还没等它结束就去读 —— StringBuilder 不是线程安全的，并发读写会读到半截内容，
        // 甚至抛 ArrayIndexOutOfBoundsException。所以统一在锁内读写。
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] bytes = readAll(in);
                String text = decode(bytes);
                synchronized (output) {
                    output.append(text);
                }
            } catch (Exception ignore) {
                // 流被关闭（进程被强杀）时忽略
            }
        });
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            // 等读线程收尾，但设上限：进程被杀后管道可能迟迟不关，不能无限等
            reader.join(1000);
            return "命令超时（" + timeout + " 秒）已被强制终止。\n"
                    + "如果确实需要更长时间，请显式传 timeoutSeconds（最大 120）。\n"
                    + "已捕获的输出：\n" + tail(snapshot(output));
        }
        reader.join(2000);

        int exit = process.exitValue();
        long cost = System.currentTimeMillis() - start;
        String text = snapshot(output).strip();

        StringBuilder sb = new StringBuilder();
        sb.append("$ ").append(command).append('\n');
        sb.append("退出码: ").append(exit).append(" | 耗时: ").append(cost).append("ms\n");
        if (text.isEmpty()) {
            sb.append("(无输出)");
        } else {
            sb.append("输出:\n").append(tail(text));
        }
        if (exit != 0) {
            sb.append("\n提示：退出码非 0 表示执行失败，请看上面的错误信息。");
        }
        return sb.toString();
    }

    /**
     * 后台启动：输出重定向到日志文件，登记任务表，立即返回任务 id。
     * 命令与环境由调用方（execute）准备好并过了策略检查，这里只做
     * 「重定向 + 登记 + 不等待」。任务随会话存在，mycode 退出时 JVM 带走子进程。
     */
    private String launchBackground(String command, ProcessBuilder pb) throws IOException {
        String id = "b" + NEXT_BACKGROUND_ID.getAndIncrement();
        Path logDir = workDir.resolve(".mycode").resolve("runs");
        Files.createDirectories(logDir);
        Path logFile = logDir.resolve(id + ".log");

        Process process = pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile())).start();
        BACKGROUND.put(id, new BackgroundTask(id, process, logFile, System.currentTimeMillis()));

        return "已在后台启动: " + command + "\n"
                + "任务 id: " + id + " | 日志: .mycode/runs/" + id + ".log\n"
                + "用 task_output 工具查询运行状态与输出。";
    }

    /** 按任务 id 查后台任务，供 TaskOutputTool 使用；不存在返回 null */
    static BackgroundTask backgroundTask(String id) {
        return id == null ? null : BACKGROUND.get(id.strip());
    }

    /** 在锁内取一份输出快照，避免和读线程并发访问 StringBuilder */
    private String snapshot(StringBuilder buffer) {
        synchronized (buffer) {
            return buffer.toString();
        }
    }

    /**
     * 组装要前置到 PATH 的目录，用系统分隔符连接。
     *
     * 顺序有讲究：虚拟环境排最前，其次是便携软件 —— 同名命令以靠前的为准。
     *
     * @return 拼好的路径串；没有可加的目录时返回空串
     */
    private String buildToolPath(boolean windows) {
        List<Path> dirs = new ArrayList<>();

        // 1. 项目虚拟环境
        Path venvBin = workDir.resolve(windows ? ".venv/Scripts" : ".venv/bin");
        if (Files.isDirectory(venvBin)) dirs.add(venvBin);

        // 2. 便携软件。LibreOffice 这类安装包解压后是 tools/LibreOffice/program/soffice.exe，
        //    所以优先找 program 子目录；没有就直接用软件根目录
        Path toolsDir = workDir.resolve("tools");
        if (Files.isDirectory(toolsDir)) {
            try (Stream<Path> stream = Files.list(toolsDir)) {
                stream.filter(Files::isDirectory).sorted().forEach(sub -> {
                    Path program = sub.resolve("program");
                    dirs.add(Files.isDirectory(program) ? program : sub);
                });
            } catch (IOException ignore) {
                // 目录读不了就跳过，不能因为工具目录有问题就把命令执行整死
            }
        }

        if (dirs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Path d : dirs) {
            if (sb.length() > 0) sb.append(File.pathSeparator);
            sb.append(d.toAbsolutePath());
        }
        return sb.toString();
    }

    /** 读完整个流。命令输出可能很长，先全收再统一截断，避免边读边丢导致上下文断片 */
    private byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    /**
     * 解码输出。
     * Windows 下 cmd 和多数 Java 工具按 GBK 输出，但有些现代工具用 UTF-8，
     * 所以先严格试 UTF-8，失败再退回 GBK —— 和 CodeFile 用的是同一套思路。
     * 包内可见：TaskOutputTool 读后台日志用同一个解码规则。
     */
    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    /** 截断输出。保留开头（错误起点）和结尾（错误汇总）各一半，中间省略 */
    private String tail(String text) {
        if (text.length() <= MAX_OUTPUT_CHARS) return text;
        int half = MAX_OUTPUT_CHARS / 2;
        String head = text.substring(0, half);
        String tail = text.substring(text.length() - half);
        return head + "\n…（中间省略 " + (text.length() - MAX_OUTPUT_CHARS) + " 字符）…\n" + tail;
    }
}
