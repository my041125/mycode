package mycode;

import java.util.List;
import java.util.Locale;

/**
 * 命令安全策略：决定一条命令能不能跑、要不要人工确认。
 *
 * 这不是「防君子」的装饰，而是 run_command 这个工具的安全阀 ——
 * 它让模型可以直接操作终端，同时也让一条幻觉出来的 `rm -rf /` 有机会被拦住。
 *
 * 三档判定：
 *   1. 命中危险片段           -> 直接拒绝，永不放行
 *   2. 只读命令（白名单）      -> 放行，不用问
 *   3. 其它命令               -> 放行但要人工确认
 *
 * 为什么第三档不直接拒绝：命令不可能穷举（构建工具、脚本、自定义 CLI 太多了），
 * 一刀切会让工具变得没用。折中办法是「默认都要问一遍」。
 * @author my
 */
public class CommandPolicy {

    /**
     * 判定结果。
     *
     * @param allowed        是否允许执行
     * @param needsApproval  是否需要人工确认
     * @param reason         拒绝或需要确认的原因，会展示给用户和模型
     */
    public record Decision(boolean allowed, boolean needsApproval, String reason) {
    }

    /**
     * 危险命令特征。命中任意一条直接拒绝，不给确认的机会 ——
     * 因为用户很可能疲劳点击「确认」，而这种操作没有撤销余地。
     */
    private static final List<String> DANGEROUS = List.of(
            "rm -rf /", "rm -rf ~", "rm -rf .", "rm -fr /",
            "del /f /s /q", "del /s /q", "format ", "rd /s /q", "rmdir /s /q",
            "shutdown", "reboot", "taskkill /f /im",
            "diskpart", "mkfs", "dd if=", "> /dev/sd", "chmod -r 777 /",
            "reg delete", "bcdedit",
            ":(){", "curl | bash", "curl|bash", "wget | sh",
            "git push --force", "git reset --hard", "git clean -fd",
            "git push -f");

    /**
     * 只读命令白名单：这些跑起来不会有副作用，不用打断用户。
     * 匹配的是「命令的第一个词」。
     */
    private static final List<String> READ_ONLY = List.of(
            "ls", "dir", "pwd", "cd", "cat", "type", "head", "tail", "wc",
            "find", "grep", "findstr", "where", "which", "echo", "date", "whoami",
            "mvn -v", "mvn -version", "mvn --version", "java -version", "javac -version",
            "node -v", "npm -v", "python --version", "git status", "git diff",
            "git log", "git branch", "git show", "git remote");

    /** 命中这些片段说明可能在读取密钥文件，禁止让内容进入模型上下文 */
    private static final List<String> SENSITIVE = List.of(
            "agent.properties", ".env", "id_rsa", "credentials", ".npmrc");

    /**
     * 评估一条命令。
     *
     * @param command 模型想执行的命令
     * @return 判定结果
     */
    public static Decision evaluate(String command) {
        if (command == null || command.isBlank()) {
            return new Decision(false, false, "命令为空");
        }
        String cmd = command.strip();
        String lower = cmd.toLowerCase(Locale.ROOT);

        // 1. 危险命令，永不执行
        for (String bad : DANGEROUS) {
            if (lower.contains(bad)) {
                return new Decision(false, false,
                        "命中危险命令特征「" + bad + "」，已拒绝。这类操作不可撤销，如需执行请你自己在终端里做");
            }
        }

        // 2. 读取密钥文件，拒绝：内容会进入模型上下文，等同于泄露
        for (String s : SENSITIVE) {
            if (lower.contains(s) && startsWithAny(lower, List.of("cat", "type", "more", "head", "tail"))) {
                return new Decision(false, false,
                        "检测到尝试读取敏感配置文件（" + s + "），已拒绝，避免密钥进入对话上下文");
            }
        }

        // 3. 只读命令直接放行
        for (String safe : READ_ONLY) {
            if (lower.equals(safe) || lower.startsWith(safe + " ")) {
                return new Decision(true, false, "只读命令，直接执行");
            }
        }

        // 4. 其余一律需要人工确认
        return new Decision(true, true, "该命令可能产生副作用，需要你确认后才会执行");
    }

    /** 判断命令是否以给定词开头（按空格切分，避免 "lsass" 被当成 "ls"） */
    private static boolean startsWithAny(String command, List<String> prefixes) {
        String first = command.split("\\s+")[0];
        for (String p : prefixes) {
            if (first.equals(p)) return true;
        }
        return false;
    }

    private CommandPolicy() {
    }
}
