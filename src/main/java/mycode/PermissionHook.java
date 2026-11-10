package mycode;

import java.io.BufferedReader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限钩子：在每次工具执行前做一次「能不能做」的判断。
 *
 * 放在 Hook 里而不是写在 Agent 循环里，是因为权限属于「策略」，
 * 而循环属于「机制」—— 同一套循环，换个钩子就能变成只读助手或全放行模式。
 *
 * 判断顺序：
 *   1. 会话内已放行     -> 用户此前对该命令选过「始终允许」，直接放行
 *   2. 只读模式         -> 编辑类、执行类工具一律拒绝
 *   3. run_command      -> 先过 CommandPolicy：危险命令拒绝，只读命令放行，其余问用户
 *   4. 其它工具         -> 放行
 * @author my
 */
public class PermissionHook implements Hook {

    /**
     * 人工确认的三态结果。
     * ALWAYS_ALLOW 是「本会话内同一命令不再询问」—— 比逐条确认省事，
     * 又比全放行保守；退出进程即失效，不做持久化，安全边界保持收紧。
     */
    public enum Decision {
        /** 放行这一次 */
        ALLOW,
        /** 拒绝这一次 */
        DENY,
        /** 放行，并把该命令记入本会话的放行清单 */
        ALWAYS_ALLOW
    }

    /** 人工确认器。抽成接口是为了能在非交互场景（管道、测试）里换成自动实现 */
    @FunctionalInterface
    public interface Confirmer {
        /**
         * @param message 要展示给用户的确认提示
         * @return 三态决定
         */
        Decision confirm(String message);
    }

    /** 编辑类工具：会修改磁盘文件 */
    private static final String TOOL_EDIT = "edit_code";

    /** 执行类工具：会影响系统状态 */
    private static final String TOOL_COMMAND = "run_command";

    private final PermissionMode mode;
    private final Confirmer confirmer;

    /** 本会话内用户选过「始终允许」的命令原文。并发集合：并行工具执行时会被读到 */
    private final Set<String> sessionApproved = ConcurrentHashMap.newKeySet();

    public PermissionHook(PermissionMode mode, Confirmer confirmer) {
        this.mode = mode;
        this.confirmer = confirmer;
    }

    /**
     * 按模式创建钩子。
     *
     * @param mode  权限模式
     * @param stdin 交互式输入流；传 null 表示非交互场景（管道、测试），
     *              此时「需要确认」的命令一律拒绝，只读命令照常执行。
     *              这里只用它判断「有没有交互环境」，按键由 Ui 的输入泵统一读取
     */
    public static PermissionHook forMode(PermissionMode mode, BufferedReader stdin) {
        if (mode == PermissionMode.FULL) {
            return new PermissionHook(mode, m -> Decision.ALLOW);
        }
        if (stdin == null) {
            return new PermissionHook(mode, m -> {
                System.out.println("    ✗ 非交互环境下无法确认，已拒绝。需要放行请加 --permission=full");
                return Decision.DENY;
            });
        }
        return new PermissionHook(mode, consoleConfirmer());
    }

    /**
     * Claude Code 式选项确认：↑/↓ 移动、回车确认，或直接按数字秒选，Esc 取消。
     * 比老的 y/N/a 手感好 —— 不用记按键，选项就写在屏幕上。
     */
    private static Confirmer consoleConfirmer() {
        return message -> {
            int choice = Ui.pickOption(List.of("允许一次", "本会话始终允许", "取消"), 2);
            return switch (choice) {
                case 0 -> {
                    System.out.println("    " + Ui.dim("✓ 允许这一次"));
                    yield Decision.ALLOW;
                }
                case 1 -> {
                    System.out.println("    " + Ui.dim("✓ 本会话内同一命令不再询问"));
                    yield Decision.ALWAYS_ALLOW;
                }
                default -> {
                    System.out.println("    " + Ui.dim("✗ 已取消该命令"));
                    yield Decision.DENY;
                }
            };
        };
    }

    @Override
    public boolean beforeTool(String name, Map<String, Object> args) {
        String command = String.valueOf(args.getOrDefault("command", ""));

        // 0. 会话内已放行：同一命令不问第二遍。放行清单只会收录策略允许过的命令，
        //    危险命令根本到不了确认环节，所以这里不可能成为危险命令的后门
        if (TOOL_COMMAND.equals(name) && sessionApproved.contains(command)) {
            return true;
        }

        // 1. 只读模式：编辑一律拒绝；执行命令只放行「无副作用」的那些
        //    （以前这里把 run_command 一刀切拒绝，导致只读模式下连 ls、git status 都跑不了，
        //      属于逻辑错误：只读模式应该允许读，而不是禁止一切命令）
        if (mode == PermissionMode.READ_ONLY) {
            if (TOOL_EDIT.equals(name)) {
                System.out.println("    ✗ 只读模式，已拒绝 " + name + "（需要改文件请用 --permission=edit）");
                return false;
            }
            if (TOOL_COMMAND.equals(name)) {
                CommandPolicy.Decision decision = CommandPolicy.evaluate(command);
                if (!decision.allowed()) {
                    System.out.println("    ✗ " + decision.reason());
                    return false;
                }
                if (decision.needsApproval()) {
                    System.out.println("    ✗ 只读模式只允许无副作用的命令，已拒绝：" + command);
                    return false;
                }
                return true;   // 只读命令放行
            }
            return true;
        }

        // 2. 命令执行：交给策略判定
        if (TOOL_COMMAND.equals(name)) {
            CommandPolicy.Decision decision = CommandPolicy.evaluate(command);
            if (!decision.allowed()) {
                System.out.println("    ✗ " + decision.reason());
                return false;
            }
            if (!decision.needsApproval()) {
                return true;   // 只读命令，不打扰用户
            }
            Decision d = confirmer.confirm("执行命令 " + command + " ?");
            switch (d) {
                case ALLOW -> { return true; }
                case ALWAYS_ALLOW -> {
                    sessionApproved.add(command);
                    System.out.println("    " + Ui.dim("✓ 本会话内同一命令不再询问"));
                    return true;
                }
                case DENY -> {
                    System.out.println("    ✗ 已取消该命令");
                    return false;
                }
            }
        }

        // 3. 其它工具（读文件、列目录、搜索）一律放行
        return true;
    }
}
