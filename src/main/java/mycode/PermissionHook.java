package mycode;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Map;

/**
 * 权限钩子：在每次工具执行前做一次「能不能做」的判断。
 *
 * 放在 Hook 里而不是写在 Agent 循环里，是因为权限属于「策略」，
 * 而循环属于「机制」—— 同一套循环，换个钩子就能变成只读助手或全放行模式。
 *
 * 判断顺序：
 *   1. 只读模式     -> 编辑类、执行类工具一律拒绝
 *   2. run_command  -> 先过 CommandPolicy：危险命令拒绝，只读命令放行，其余问用户
 *   3. 其它工具     -> 放行
 */
public class PermissionHook implements Hook {

    /** 人工确认器。抽成接口是为了能在非交互场景（管道、测试）里换成自动实现 */
    @FunctionalInterface
    public interface Confirmer {
        /**
         * @param message 要展示给用户的确认提示
         * @return true 表示允许执行
         */
        boolean confirm(String message);
    }

    /** 编辑类工具：会修改磁盘文件 */
    private static final String TOOL_EDIT = "edit_code";

    /** 执行类工具：会影响系统状态 */
    private static final String TOOL_COMMAND = "run_command";

    private final PermissionMode mode;
    private final Confirmer confirmer;

    public PermissionHook(PermissionMode mode, Confirmer confirmer) {
        this.mode = mode;
        this.confirmer = confirmer;
    }

    /**
     * 按模式创建钩子。
     *
     * @param mode  权限模式
     * @param stdin 交互式输入流；传 null 表示非交互场景（管道、测试），
     *              此时「需要确认」的命令一律拒绝，只读命令照常执行
     */
    public static PermissionHook forMode(PermissionMode mode, BufferedReader stdin) {
        if (mode == PermissionMode.FULL) {
            return new PermissionHook(mode, m -> true);
        }
        if (stdin == null) {
            return new PermissionHook(mode, m -> {
                System.out.println("    ✗ 非交互环境下无法确认，已拒绝。需要放行请加 --permission=full");
                return false;
            });
        }
        return new PermissionHook(mode, consoleConfirmer(stdin));
    }

    /** 命令行确认：打印提示，读一行，y/yes 视为同意 */
    private static Confirmer consoleConfirmer(BufferedReader reader) {
        return message -> {
            System.out.print("  ? " + message + " [y/N] ");
            System.out.flush();
            try {
                String line = reader.readLine();
                if (line == null) return false;
                String v = line.strip().toLowerCase();
                return v.equals("y") || v.equals("yes");
            } catch (IOException e) {
                return false;
            }
        };
    }

    @Override
    public boolean beforeTool(String name, Map<String, Object> args) {
        // 1. 只读模式：一切有副作用的操作都拒绝
        if (mode == PermissionMode.READ_ONLY
                && (TOOL_EDIT.equals(name) || TOOL_COMMAND.equals(name))) {
            System.out.println("    ✗ 只读模式，已拒绝 " + name + "（需要写操作请用 --permission=edit）");
            return false;
        }

        // 2. 命令执行：交给策略判定
        if (TOOL_COMMAND.equals(name)) {
            String command = String.valueOf(args.getOrDefault("command", ""));
            CommandPolicy.Decision decision = CommandPolicy.evaluate(command);
            if (!decision.allowed()) {
                System.out.println("    ✗ " + decision.reason());
                return false;
            }
            if (!decision.needsApproval()) {
                return true;   // 只读命令，不打扰用户
            }
            boolean ok = confirmer.confirm("执行命令 " + command + " ?");
            if (!ok) {
                System.out.println("    ✗ 已取消该命令");
            }
            return ok;
        }

        // 3. 其它工具（读文件、列目录、搜索）一律放行
        return true;
    }

    /** 当前模式，界面展示用 */
    public PermissionMode mode() {
        return mode;
    }
}
