package mycode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目记忆：启动时自动加载的「项目约定」，拼进系统提示词。
 *
 * 对齐主流 agent 的做法 —— Codex 读 AGENTS.md，Claude Code 读 CLAUDE.md。
 * 内容一般是：构建/测试命令、代码风格、目录结构说明。没有它，
 * 用户每次会话都得口头交代一遍「用 mvn 编译、注释写中文」这类固定约定。
 *
 * 查找顺序（项目级取第一个命中的，避免三份内容互相打架）：
 *   <项目>/mycode.md  →  <项目>/AGENTS.md  →  <项目>/CLAUDE.md
 * 另加载用户级 ~/.claude/CLAUDE.md（Claude Code 的全局记忆，跨项目通用）。
 *
 * 记忆是「背景信息」不是「指令」：超长截断，读不了就当没有，绝不阻断启动。
 * @author my
 */
public class ProjectMemory {

    /** 单份记忆文件的截断长度。约定写这么长 usually 已经该精简了 */
    private static final int FILE_LIMIT = 4000;

    /** 拼好待注入的正文（含来源标注），可能为空串 */
    private final String injectText;

    /** 命中的记忆文件（相对/波浪线路径），供 /memory 展示来源 */
    private final List<String> sources;

    private ProjectMemory(String injectText, List<String> sources) {
        this.injectText = injectText;
        this.sources = sources;
    }

    /** 扫描所有位置。任何一步失败都静默跳过 —— 记忆是可选能力 */
    public static ProjectMemory discover(Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        StringBuilder text = new StringBuilder();
        List<String> sources = new ArrayList<>();

        // 1. 项目级：mycode.md 优先（自有格式），其余两个是为了直接复用
        //    Codex / Claude Code 用户的现成文件
        String project = firstExisting(root,
                List.of("mycode.md", "AGENTS.md", "CLAUDE.md"));
        if (project != null) {
            append(text, sources, root.resolve(project), project);
        }

        // 2. 用户级：Claude Code 的全局记忆
        String home = System.getProperty("user.home");
        if (home != null) {
            Path userFile = Path.of(home, ".claude", "CLAUDE.md");
            if (Files.isRegularFile(userFile)) {
                append(text, sources, userFile, "~/.claude/CLAUDE.md");
            }
        }

        String inject = text.length() == 0 ? "" : "\n\n" + text.toString().strip();
        return new ProjectMemory(inject, sources);
    }

    /** 返回第一个存在的文件名；都不存在返回 null */
    private static String firstExisting(Path root, List<String> candidates) {
        for (String name : candidates) {
            if (Files.isRegularFile(root.resolve(name))) return name;
        }
        return null;
    }

    /** 读一份记忆文件并追加。单份超长截断，注明省略 */
    private static void append(StringBuilder text, List<String> sources, Path file, String label) {
        try {
            String content = CodeFile.read(file).text().strip();
            if (content.isEmpty()) return;
            if (content.length() > FILE_LIMIT) {
                content = content.substring(0, FILE_LIMIT) + "\n…（记忆过长已截断）";
            }
            if (text.length() > 0) text.append("\n\n");
            text.append("【项目约定】(来自 ").append(label).append(")\n").append(content);
            sources.add(label);
        } catch (Exception ignore) {
            // 单个文件读不了不影响其它记忆
        }
    }

    /** 是否加载到了任何记忆 */
    public boolean isEmpty() {
        return sources.isEmpty();
    }

    /** 命中的记忆文件清单，供 /memory 展示 */
    public List<String> sources() {
        return new ArrayList<>(sources);
    }

    /** 拼进系统提示词的正文，空记忆返回空串 */
    public String inject() {
        return injectText;
    }
}
