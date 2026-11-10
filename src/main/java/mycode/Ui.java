package mycode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 终端界面小工具：统一负责 Claude Code 风格的外观 —— 圆角框、转圈等待、暗色提示、
 * 橙色强调，以及让这一切能对齐的「中文按两列计宽」和「终端宽度实时探测」。
 *
 * 为什么要单独一个类：Shell、ConsoleHook、PermissionHook 都要往终端画东西，
 * 「一个汉字占几列」「ANSI 颜色能不能用」「窗口有多宽」这些规则必须全项目只有一份，
 * 各画各的必然对不齐、颜色时有时无。
 *
 * 宽度探测（每次画框前重新探测，窗口拉伸/缩放后下一轮提示符自动跟上）：
 *   1. agent.properties 的 termWidth=NN 强制指定，配了就用它；
 *   2. 有真实控制台的 Windows（cmd / Windows Terminal，含缩放）—— 问 mode con。
 *      它直接读控制台状态，是操作系统的权威答案。实测 ConPTY 下 DSR 应答会漂移
 *      （两次探测给出不同列数、且不等于真实窗口宽），所以这里不用 DSR；
 *   3. 拿不到控制台的终端（mintty / VSCode 的 bash）—— 发 DSR 光标位置询问
 *      （\u001b[999C\u001b[6n），终端把「列数」回答到 stdin，由 ConsoleInput
 *      输入泵拦下，绝不混进用户输入；
 *   4. 都不行（管道/重定向）—— 用 COLUMNS 环境变量，再不行 80 列。
 *
 * 颜色策略：默认按终端环境自动判断（TERM / WT_SESSION / ConEmuANSI / ANSICON
 * 任一存在就启用），也可在 agent.properties 里用 ansi=on / off 强制。
 * 框线和符号本身是 UTF-8 字符（mycode.bat 已 chcp 65001），不依赖 ANSI。
 * @author my
 */
public final class Ui {

    /** 兜底宽度：什么都探测不到时按最经典的 80 列画 */
    private static final int DEFAULT_WIDTH = 80;

    // ---------------- 颜色 ----------------

    private static final boolean ANSI = detectAnsi();

    /** 按配置和环境判断要不要输出 ANSI 转义码 */
    private static boolean detectAnsi() {
        String pref = AgentConfig.get("ansi");
        if (pref != null && !pref.isBlank()) {
            String v = pref.strip().toLowerCase();
            if (v.equals("on") || v.equals("true")) return true;
            if (v.equals("off") || v.equals("false")) return false;
        }
        // TERM：Linux/macOS/Git Bash；WT_SESSION：Windows Terminal；
        // ConEmuANSI / ANSICON：老牌 Windows 增强终端
        return System.getenv("TERM") != null
                || System.getenv("WT_SESSION") != null
                || "on".equalsIgnoreCase(System.getenv("ConEmuANSI"))
                || System.getenv("ANSICON") != null;
    }

    /** 暗色：次要信息、统计行 */
    public static String dim(String s) { return wrap("\u001b[2m", s); }

    /** 强调色：Claude Code 的橙色 */
    public static String accent(String s) { return wrap("\u001b[38;5;208m", s); }

    /** 加粗 */
    public static String bold(String s) { return wrap("\u001b[1m", s); }

    private static String wrap(String code, String s) {
        return ANSI ? code + s + "\u001b[0m" : s;
    }

    // ---------------- 宽度与对齐 ----------------

    /** 终端显示宽度：CJK 字符按 2 列，其余按 1 列。框线对齐全靠它 */
    public static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            w += cpWidth(cp);
        }
        return w;
    }

    /** 一个码点占几列 */
    private static int cpWidth(int cp) {
        if (cp >= 0x2E80 && cp <= 0x9FFF) return 2;    // CJK 部首、汉字、注音、CJK 标点
        if (cp >= 0xAC00 && cp <= 0xD7AF) return 2;    // 朝鲜文音节
        if (cp >= 0xF900 && cp <= 0xFAFF) return 2;    // CJK 兼容表意
        if (cp >= 0xFF00 && cp <= 0xFF60) return 2;    // 全角形式（！？ＡＢ）
        if (cp >= 0x20000 && cp <= 0x3FFFD) return 2;  // 汉字扩展区
        return 1;                                       // 框线╭│╯、符号⏺⎿✻ 都是 1 列
    }

    /** 右侧补空格到指定显示宽度 */
    public static String pad(String s, int width) {
        int gap = width - width(s);
        return gap > 0 ? s + " ".repeat(gap) : s;
    }

    /** 超宽就截断加省略号，保证框不被撑破 */
    public static String fit(String s, int width) {
        if (width(s) <= width) return s;
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int cw = cpWidth(cp);
            if (w + cw > width - 1) break;
            sb.appendCodePoint(cp);
            w += cw;
        }
        return sb + "…";
    }

    // ---------------- 终端宽度探测 ----------------

    /** termWidth 配置（0 表示未配置）。配了就完全不听终端的，适合怪终端 */
    private static final int FORCED_WIDTH = intConfig("termWidth");

    /** DSR 探测是否可用：null=还没试过；false=失败过，本次会话不再试（省 200ms/轮） */
    private static volatile Boolean dsrOk = null;

    /** 输入泵。DSR 探测的响应走 stdin，必须由它统一接收，不能让 BufferedReader 吞掉 */
    private static final ConsoleInput INPUT = new ConsoleInput();

    /** 本轮输入框的内容区宽度。promptClose 要与上半框同宽，中途改窗口也不许变 */
    private static int promptInner;

    /** 会话唯一的标准输入流：Shell 的 BufferedReader 必须包着它，而不是直接包 System.in */
    public static InputStream consoleInput() {
        INPUT.ensureStarted();
        return INPUT;
    }

    /** 当前终端宽度（列）。每次画框前调用 */
    static int termWidth() {
        if (FORCED_WIDTH >= 40) return FORCED_WIDTH;

        // Windows 且有真实控制台：mode con 是操作系统给的标准答案，
        // 窗口拉伸、Ctrl+缩放（字号变大变小）都会如实反映。ConPTY 下 DSR 会漂移，不可信
        if (isWindows() && System.console() != null) {
            int w = modeConWidth();
            if (w >= 40) return Math.min(w, 300);
        }

        // 拿不到控制台（mintty / VSCode bash）或 mode con 失败：退到 DSR 光标询问
        if (ANSI && dsrOk != Boolean.FALSE) {
            int w = INPUT.probeWidth();
            if (w >= 40) {
                dsrOk = Boolean.TRUE;
                return Math.min(w, 300);
            }
            dsrOk = Boolean.FALSE;   // 失败过就不再等超时，管道场景尤其必要
        }
        return fallbackWidth();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** COLUMNS 环境变量，再兜底 80 */
    private static int fallbackWidth() {
        String cols = System.getenv("COLUMNS");
        if (cols != null) {
            try {
                int v = Integer.parseInt(cols.strip());
                if (v >= 40) return v;
            } catch (NumberFormatException ignore) {
            }
        }
        return DEFAULT_WIDTH;
    }

    /**
     * 问 cmd 的 mode con：直接读控制台状态，拉伸/缩放后的窗口宽度都是准的。
     * 中文系统输出「列: 120」，英文系统「Columns: 120」；都匹配不上时
     * 取输出里最后一个 10~500 的整数兜底（行数在前、列数在后，最后一个是列）。
     */
    private static int modeConWidth() {
        if (!isWindows()) return -1;
        try {
            Process p = new ProcessBuilder("cmd.exe", "/c", "mode", "con")
                    .redirectErrorStream(true).start();
            if (!p.waitFor(1, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            // 中文 Windows 的 cmd 输出是 GBK；数字本身是 ASCII，解码只影响
            // 「列/Columns」这个词能不能匹配上，用 GBK 解两头都能命中
            String out = new String(p.getInputStream().readAllBytes(), Charset.forName("GBK"));
            Matcher m = Pattern.compile("(?:列|Columns)\\D*(\\d+)").matcher(out);
            if (m.find()) return Integer.parseInt(m.group(1));
            // 其他语言的 Windows：mode con 的布局各国一致 —— 第 1 个数值行是行数，
            // 第 2 个是列数（后面还有键盘/码页行，绝不能取「最后一个」）
            Matcher line = Pattern.compile(":\\s+(\\d+)\\s*$", Pattern.MULTILINE).matcher(out);
            for (int i = 0; i < 2 && line.find(); i++) {
                if (i == 1) return Integer.parseInt(line.group(1));
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 内容区宽度 = 终端宽度 - 左右边框和空格的 4 列，夹在合理区间 */
    private static int innerWidth() {
        int inner = termWidth() - 4;
        return Math.max(30, Math.min(inner, 220));
    }

    // ---------------- 圆角框 ----------------

    /**
     * 画一个圆角信息框（启动横幅用）。第一行按标题处理：橙色加粗。
     * 内容先 fit 截断再 pad 对齐，长路径不会把框撑破。
     */
    public static void box(List<String> lines) {
        System.out.println();                      // 探测前先换行，保证光标在第 1 列
        int inner = innerWidth();
        System.out.println(dim("╭" + "─".repeat(inner + 2) + "╮"));
        for (int i = 0; i < lines.size(); i++) {
            String content = pad(fit(lines.get(i), inner), inner);
            System.out.println("│ " + (i == 0 ? accent(bold(content)) : content) + " │");
        }
        System.out.println(dim("╰" + "─".repeat(inner + 2) + "╯"));
    }

    // ---------------- 输入框 ----------------

    /**
     * 画输入框的上半部分并给出 › 提示符，用户在这一行里输入。
     *
     * 纯行式读取（BufferedReader.readLine）拿不到用户敲键的过程，
     * 没法像真正的 TUI 那样实时画右框线，所以采用近似：
     * 先画「│ › 」，用户回车后由 promptClose 补上右竖线和下半框。
     * 观感与 Claude Code 的输入框基本一致。
     */
    public static void promptOpen() {
        System.out.println();                      // 同上：探测要求光标在第 1 列
        promptInner = innerWidth();
        System.out.println(dim("╭" + "─".repeat(promptInner + 2) + "╮"));
        System.out.print("│ › ");
        System.out.flush();
    }

    /**
     * 用户回车后补齐输入框：右竖线 + 下边框。
     * 用 promptOpen 时记下的宽度 —— 就算用户输入途中拉伸了窗口，
     * 下半框也必须和上半框对齐。
     *
     * @param typed 用户刚输入的原文（EOF 时为 null，按空处理）
     */
    public static void promptClose(String typed) {
        int w = typed == null ? 0 : width(typed);
        if (w <= promptInner - 2) {
            // “› ” 前缀占 2 列，右竖线占 1 列，剩余的就是补白
            System.out.println(" ".repeat(promptInner - 2 - w) + " │");
        } else {
            System.out.println();
        }
        System.out.println(dim("╰" + "─".repeat(promptInner + 2) + "╯"));
    }

    // ---------------- 转圈等待 ----------------

    /** 全局唯一的「思考中」转圈。ConsoleHook 启停，流式输出打第一个字前也要停掉它 */
    private static final Spinner THINK = new Spinner();

    /** 开始转圈。非交互（管道/重定向）环境下自动变空操作 */
    public static void startThinking() { THINK.start(); }

    /** 停止转圈并擦掉那一行。幂等，重复调用无副作用 */
    public static void stopThinking() { THINK.stop(); }

    // ---------------- Claude Code 式选项确认 ----------------

    /**
     * 选项选择器：↑/↓ 移动高亮、回车确认、数字 1~n 秒选、Esc 取消。
     *
     *   ❯ 1. 允许一次
     *     2. 本会话始终允许
     *     3. 取消
     *
     * 按键直接从 {@link ConsoleInput} 泵读字节 —— REPL 的 BufferedReader 只按行
     * 消费，收不到方向键。行缓冲的终端（老式 cmd）收不到方向键也没关系：
     * 「数字 + 回车」同样能选，两种形态都覆盖。
     *
     * @param options     选项文本（下标 0 起）
     * @param cancelIndex 取消时返回的下标（通常是「取消」那一项）
     * @return 选中的选项下标；Esc / Ctrl+D / 输入流关闭返回 cancelIndex
     */
    public static int pickOption(List<String> options, int cancelIndex) {
        INPUT.ensureStarted();
        int selected = 0;
        boolean first = true;

        while (true) {
            int b;
            try {
                b = INPUT.read();
            } catch (IOException e) {
                return cancelIndex;
            }
            if (b == -1) return cancelIndex;   // Ctrl+D / 输入流结束

            if (b == 0x1B) {
                // Esc 键和方向键都以 0x1B 开头：短暂等下一个字节来区分。
                // 等不到 → 单独的 Esc，视为取消；等到 '[' → 转义序列
                int next = peek(120);
                if (next == -1) return cancelIndex;
                if (next == '[') {
                    int cmd = peek(120);
                    if (cmd == 'A' || cmd == 'B') {
                        int n = options.size();
                        selected = cmd == 'A'
                                ? (selected + n - 1) % n
                                : (selected + 1) % n;
                        renderOptions(options, selected, !first);
                        first = false;
                    } else if (cmd >= 0) {
                        skipSequence(cmd);   // Home/End 等其它序列，跳过不响应
                    } else {
                        return cancelIndex;  // 半截序列超时，按 Esc 处理
                    }
                }
                continue;
            }

            if (b == '\r' || b == '\n') {
                drainEnter();
                return selected;
            }
            if (b >= '1' && b <= '0' + options.size()) {
                selected = b - '1';
                renderOptions(options, selected, !first);
                first = false;
                drainEnter();
                return selected;
            }
            // 其它按键（包括中文输入的半个字节）忽略
        }
    }

    /** 画出选项列表。redraw 时先把光标移回选项首行逐行重写 */
    private static void renderOptions(List<String> options, int selected, boolean redraw) {
        StringBuilder sb = new StringBuilder();
        if (redraw && ANSI && options.size() > 1) {
            sb.append("\u001b[").append(options.size() - 1).append('A');   // 光标上移到选项首行
        }
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) sb.append('\n');
            // 回行首并清到行尾：重画时抹掉旧内容，也顺带抹掉数字秒选时终端的回显
            sb.append(ANSI ? "\r\u001b[K" : "\r");
            sb.append(i == selected ? accent("❯ ") : "  ");
            sb.append(i + 1).append(". ").append(options.get(i));
        }
        System.out.print(sb);
        System.out.flush();
    }

    /** 等最多 timeout 毫秒看下一个字节；没等到返回 -1 */
    private static int peek(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (INPUT.available() > 0) {
                try {
                    return INPUT.read();
                } catch (IOException e) {
                    return -1;
                }
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        return -1;
    }

    /** 跳过一个非方向键转义序列的剩余部分（参数数字、分号、终止字母） */
    private static void skipSequence(int first) {
        int b = first;
        while (b >= '0' && b <= '9' || b == ';' || b == '?') {
            b = peek(120);
        }
    }

    /**
     * 选中后把残留的回车吃掉（行缓冲终端按「数字+回车」时会有 \r/\n 排队），
     * 否则它会被 REPL 的下一次 readLine 读成一条空指令。
     */
    private static void drainEnter() {
        long deadline = System.currentTimeMillis() + 80;
        while (System.currentTimeMillis() < deadline && INPUT.available() > 0) {
            try {
                if (INPUT.read() == '\n') return;
            } catch (IOException e) {
                return;
            }
        }
    }

    /**
     * 动画转圈：Claude Code 风格的 ✻ 系符号 + 随机动词（琢磨中…/盘算中…）。
     *
     * 单独开守护线程按 \r 原地重画；主线程阻塞在模型调用上，互不抢输出。
     * stop() 先置中断再短暂 join，保证擦线动作一定发生在动画最后一帧之后。
     */
    static final class Spinner {
        private static final String[] FRAMES = {"✻", "✽", "✳", "✶", "✷"};
        private static final String[] VERBS = {"思考", "琢磨", "盘算", "推敲", "翻阅", "梳理", "捣鼓", "校对", "搜罗", "合计"};

        /** 擦线宽度：盖住最长的一帧即可 */
        private static final int LINE = 20;

        private Thread thread;

        synchronized void start() {
            // 管道/重定向下没有 System.console()，\r 动画只会污染输出
            if (System.console() == null || thread != null) return;
            // 只是挑个展示用动词，不需要加密强度随机，ThreadLocalRandom 足够且无锁
            String verb = VERBS[ThreadLocalRandom.current().nextInt(VERBS.length)];
            thread = new Thread(() -> {
                for (int i = 0; !Thread.currentThread().isInterrupted(); i++) {
                    System.out.print("\r  " + accent(FRAMES[i % FRAMES.length]) + " " + verb + "中…   ");
                    System.out.flush();
                    try {
                        Thread.sleep(130);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "mycode-spinner");
            thread.setDaemon(true);
            thread.start();
        }

        synchronized void stop() {
            if (thread == null) return;
            thread.interrupt();
            try {
                thread.join(300);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
            System.out.print("\r" + " ".repeat(LINE) + "\r");
            System.out.flush();
            thread = null;
        }
    }

    // ---------------- 标准输入泵 ----------------

    /**
     * 标准输入的唯一读者：后台线程裸读 System.in，把 DSR 光标响应
     * （\u001b[行;列R）拦截下来交给探测逻辑，其余字节原样放进队列。
     *
     * 为什么必须这样做：终端对 DSR 的回答混在 stdin 里，如果让 REPL 的
     * BufferedReader 直接读，回答会拼进用户输入的开头；而探测线程和
     * BufferedReader 同时读 System.in 又会互相抢字节。让一个线程当泵、
     * 两个消费方（读行 / 读探测结果）各取所需，问题就没了。
     *
     * 字节层面拦截是安全的：DSR 响应是纯 ASCII，而 UTF-8 的多字节字符
     * 每个字节都 ≥ 0x80，ESC/[/R 这些分隔符不可能出现在汉字字节中间。
     */
    static final class ConsoleInput extends InputStream {

        /** 探测超时（毫秒）：非 ANSI 终端不会有回答，等太久拖慢每一次画框 */
        private static final long PROBE_TIMEOUT = 200;

        private final LinkedBlockingQueue<Integer> queue = new LinkedBlockingQueue<>();

        /** 最近一次 DSR 回答的列数与到达时间。时间戳用来丢弃迟到/陈旧的回答 */
        private volatile int responseCol = -1;
        private volatile long responseNanos = 0;

        /** 已经见过 EOF 哨兵。批量读时哨兵不能被消费掉，否则下一次读会永远阻塞 */
        private volatile boolean eofSeen = false;

        private final Thread pump = new Thread(this::pumpLoop, "mycode-stdin");

        ConsoleInput() {
            pump.setDaemon(true);
        }

        synchronized void ensureStarted() {
            if (pump.getState() == Thread.State.NEW) pump.start();
        }

        private void pumpLoop() {
            byte[] buf = new byte[4096];
            // 状态机：0=普通字节 1=刚见到 ESC 2=ESC[ 之后收参数
            StringBuilder esc = new StringBuilder();
            int state = 0;
            try {
                int n;
                while ((n = System.in.read(buf)) > 0) {
                    for (int i = 0; i < n; i++) {
                        int b = buf[i] & 0xFF;
                        switch (state) {
                            case 0 -> {
                                if (b == 0x1B) { esc.setLength(0); esc.append('\u001b'); state = 1; }
                                else queue.add(b);
                            }
                            case 1 -> {
                                if (b == '[') { esc.append('['); state = 2; }
                                else { flushEsc(esc); queue.add(b); state = 0; }
                            }
                            default -> {
                                char c = (char) b;
                                if ((c >= '0' && c <= '9') || c == ';') {
                                    esc.append(c);
                                    if (esc.length() > 24) { flushEsc(esc); state = 0; }   // 太长，不是 DSR
                                } else if (c == 'R') {
                                    acceptCpr(esc);   // \u001b[行;列R —— 这就是探测的答案
                                    state = 0;
                                } else {
                                    flushEsc(esc);   // 方向键等别的序列，原样放行
                                    queue.add(b);
                                    state = 0;
                                }
                            }
                        }
                    }
                }
            } catch (IOException ignore) {
                // stdin 关闭或出错：把已缓冲的内容留给消费者，线程安静退出
            }
            queue.add(-1);   // EOF 哨兵
        }

        /** 把不是 DSR 响应的转义序列原样放回队列（用户可能粘贴了特殊按键序列） */
        private void flushEsc(StringBuilder esc) {
            for (int i = 0; i < esc.length(); i++) queue.add((int) esc.charAt(i));
        }

        /** 解析 \u001b[行;列R，记下列数与到达时刻 */
        private void acceptCpr(StringBuilder esc) {
            String s = esc.toString();
            int semi = s.indexOf(';');
            if (semi < 2) return;
            try {
                responseCol = Integer.parseInt(s.substring(semi + 1));
                responseNanos = System.nanoTime();
            } catch (NumberFormatException ignore) {
            }
        }

        /**
         * DSR 探测：光标先推到行尾（\u001b[999C），问位置（\u001b[6n），
         * 回答的列数就是终端宽度，最后把光标移回第 1 列。
         * 调用前提：光标当前在第 1 列（box/promptOpen 都是先换行再探测）。
         */
        synchronized int probeWidth() {
            ensureStarted();
            long sentAt = System.nanoTime();
            System.out.print("\u001b[999C\u001b[6n");
            System.out.flush();
            long deadline = System.currentTimeMillis() + PROBE_TIMEOUT;
            while (System.currentTimeMillis() < deadline) {
                if (responseNanos >= sentAt && responseCol > 0) {
                    int w = responseCol;
                    if (w > 1) {
                        System.out.print("\u001b[" + (w - 1) + "D");   // 光标移回第 1 列
                        System.out.flush();
                    }
                    return w;
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            return -1;
        }

        // ---------------- InputStream 接口：BufferedReader 包着它用 ----------------

        @Override
        public int read() throws IOException {
            if (eofSeen) return -1;
            try {
                Integer b = queue.take();
                if (b == -1) eofSeen = true;
                return b;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("标准输入被中断", e);
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (eofSeen) return -1;
            Integer first;
            try {
                first = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("标准输入被中断", e);
            }
            if (first == -1) {
                eofSeen = true;
                return -1;
            }
            b[off] = first.byteValue();
            int count = 1;
            while (count < len) {
                Integer more = queue.poll();   // 只取现成的，不阻塞
                if (more == null) break;
                // EOF 哨兵只置标志不下肚：丢掉它的话，后续 read 会在空队列上永远阻塞
                if (more == -1) {
                    eofSeen = true;
                    break;
                }
                b[off + count++] = more.byteValue();
            }
            return count;
        }

        @Override
        public int available() {
            return Math.min(queue.size(), Integer.MAX_VALUE);
        }
    }

    /** 读 agent.properties 里的正整数配置，读不到返回 0 */
    private static int intConfig(String key) {
        String v = AgentConfig.get(key);
        if (v == null || v.isBlank()) return 0;
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 工具类，禁止实例化 */
    private Ui() {
    }
}
