package mycode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能加载工具 use_skill。
 *
 * 为什么做成「工具」而不是启动时把技能全塞进 system：
 *   一个技能正文动辄十几 KB（pptx 那份 238 行），全塞进去每次请求都要付费；
 *   做成工具后，只有真正用到时才加载，模型自己判断"这个话题跟 docx 有关"。
 *   这就是 Agent Skills 规范里的「渐进披露」。
 */
public class SkillTool implements Tool {

    private final SkillRegistry registry;

    public SkillTool(SkillRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "use_skill";
    }

    @Override
    public String description() {
        return """
                加载指定技能的完整说明。处理 Word/Excel/PPT/PDF 等专业文件时必须先调用它，
                拿到分步骤的做法和附带脚本后再动手，不要凭常识直接写代码。
                参数 name 取技能清单里列出的名字（例如 docx、xlsx、pptx）。
                """;
    }

    @Override
    public Map<String, Object> parameters() {
        return Tool.schema(Map.of(
                "name", Tool.str("技能名，例如 docx"),
                "args", Tool.str("可选参数，会替换技能正文里的 $ARGUMENTS 占位符")),
                "name");
    }

    @Override
    public String execute(Map<String, Object> args) {
        String name = args.get("name") == null ? "" : String.valueOf(args.get("name")).strip();

        Skill skill = registry.find(name).orElseThrow(() -> new IllegalArgumentException(
                "没有名为「" + name + "」的技能。可用技能：" + names()));

        String body = skill.body();
        // $ARGUMENTS 是 Claude Code 的约定，把用户给的参数填进去
        Object extra = args.get("args");
        if (extra != null && !String.valueOf(extra).isBlank()) {
            body = body.replace("$ARGUMENTS", String.valueOf(extra).strip());
        }
        body = rewriteDynamicCommands(body);

        StringBuilder sb = new StringBuilder();
        sb.append("【技能：").append(skill.name()).append("】来源：").append(skill.source()).append('\n');
        sb.append("技能目录：").append(skill.dir().toString().replace('\\', '/')).append('\n');
        if (!skill.allowedTools().isEmpty()) {
            sb.append("原技能声明的工具：").append(String.join(", ", skill.allowedTools()))
                    .append("（本客户端对应：").append(mapTools(skill.allowedTools())).append("）\n");
        }
        sb.append("\n").append(body).append("\n");

        List<String> files = SkillRegistry.bundledFiles(skill);
        if (!files.isEmpty()) {
            sb.append("\n附带的文件（路径相对技能目录）：\n");
            files.forEach(f -> sb.append("- ").append(f).append('\n'));
            sb.append("需要跑脚本时用 run_command，路径请用上面的「技能目录」拼绝对路径。\n");
        }
        return sb.toString();
    }

    /**
     * 把 `!命令` 这类动态注入改写成说明文字，而不是直接执行。
     *
     * Claude Code 会自动执行这些命令并把输出内联。我们不这么做，原因很简单：
     * 技能来自外部，等于一段来源不明的脚本，自动执行就是给了它任意命令执行权。
     * 改成提示后，模型会自己决定要不要调 run_command —— 那条路径有权限确认把关。
     */
    private String rewriteDynamicCommands(String body) {
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("!") && trimmed.length() > 1) {
                sb.append("（此处技能原本会自动执行：").append(trimmed.substring(1).strip())
                        .append("；本客户端不自动执行，需要时自行调用 run_command）");
            } else {
                sb.append(line);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Claude Code 的工具名 -> 本客户端的工具名，方便模型换用 */
    private String mapTools(List<String> claudeTools) {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("read", "read_code");
        mapping.put("write", "edit_code");
        mapping.put("edit", "edit_code");
        mapping.put("bash", "run_command");
        mapping.put("grep", "list_files");
        mapping.put("glob", "list_files");
        mapping.put("webfetch", "fetch_page");
        mapping.put("websearch", "web_search");
        mapping.put("task", "(不支持)");

        StringBuilder sb = new StringBuilder();
        for (String t : claudeTools) {
            String ours = mapping.getOrDefault(t.toLowerCase(), "未映射");
            if (sb.length() > 0) sb.append(", ");
            sb.append(t).append("→").append(ours);
        }
        return sb.toString();
    }

    /** 可用技能名，拼成一行用于报错提示 */
    private String names() {
        List<Skill> all = registry.all();
        if (all.isEmpty()) return "（当前没有任何技能）";
        StringBuilder sb = new StringBuilder();
        for (Skill s : all) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s.name());
        }
        return sb.toString();
    }
}
