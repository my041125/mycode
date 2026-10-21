package mycode;

import java.util.Map;

/**
 * 面向命令行的钩子：把 agent 的内部动作渲染成终端里能看懂的过程。
 *
 * 输出风格参考 Claude Code：
 *   ⏺ edit_code(path="samples/TicketService.java", op="replace")
 *     ⎿  已修改 samples/TicketService.java
 *
 * 与 LoggingHook 的区别：LoggingHook 是给开发调试看的（打印上下文长度、轮次），
 * ConsoleHook 是给用户看的（只展示工具调用和结果），所以刻意去掉了噪音。
 */
public class ConsoleHook implements Hook {

    /** 参数值最长显示多少字符，代码片段太长会把终端刷乱 */
    private static final int ARG_MAX = 46;

    /** 工具结果最长显示多少字符 */
    private static final int RESULT_MAX = 88;

    /** 等待提示那行的宽度，用于回车擦除 */
    private static final int SPINNER_WIDTH = 24;

    /** 当前轮次 */
    private int step = 0;

    /** 等待提示是否已经打印出来（打印了才需要擦） */
    private boolean spinnerShown = false;

    /**
     * 是否在真实终端里。
     * 输出被重定向到文件或管道时（System.console() 为 null）不打印等待提示，
     * 否则那串回车擦除字符会留在日志里变成垃圾。
     */
    private final boolean interactive = System.console() != null;

    /** 调模型前打一个等待提示。模型响应可能好几秒，没提示用户会以为卡死 */
    @Override
    public void beforeLlm(AgentContext ctx) {
        step++;
        if (!interactive) return;
        System.out.print(step == 1 ? "  ⋯ 思考中" : "  ⋯ 继续思考");
        System.out.flush();
        spinnerShown = true;
    }

    /** 模型回来后把等待提示擦掉，避免和后续输出混在一起 */
    @Override
    public void afterLlm(ChatResult result) {
        clearSpinner();
    }

    /** 工具调用：打印成 ⏺ 名字(参数=值) 的形式 */
    @Override
    public boolean beforeTool(String name, Map<String, Object> args) {
        clearSpinner();
        System.out.println("  ⏺ " + name + "(" + renderArgs(args) + ")");
        return true;
    }

    /** 工具结果：接在调用下面，第一行即可，太长截断 */
    @Override
    public void afterTool(String name, String result) {
        String first = firstLine(result);
        System.out.println("    ⎿  " + first);
    }

    /** 把参数渲染成 键="值" 一行，超长就截断并加省略号 */
    private String renderArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        args.forEach((k, v) -> {
            if (sb.length() > 0) sb.append(", ");
            sb.append(k).append("=\"").append(shorten(v)).append('"');
        });
        return sb.toString();
    }

    /** 参数值可能是一大段代码，压成一行并截断 */
    private String shorten(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v).replace("\r", "").replace("\n", "⏎").strip();
        return s.length() <= ARG_MAX ? s : s.substring(0, ARG_MAX) + "…";
    }

    /** 取结果的第一行有效内容；结果常是多行，全打出来会淹掉界面 */
    private String firstLine(String result) {
        if (result == null || result.isBlank()) return "(无输出)";
        for (String line : result.split("\n")) {
            if (!line.isBlank()) {
                String s = line.strip();
                return s.length() <= RESULT_MAX ? s : s.substring(0, RESULT_MAX) + "…";
            }
        }
        return "(无输出)";
    }

    /** 用回车擦掉等待提示 */
    private void clearSpinner() {
        if (!spinnerShown) return;
        System.out.print("\r" + " ".repeat(SPINNER_WIDTH) + "\r");
        System.out.flush();
        spinnerShown = false;
    }
}
