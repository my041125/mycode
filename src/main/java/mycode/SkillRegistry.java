package mycode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 技能注册表：扫描磁盘上的 SKILL.md，提供「清单」和「按需加载」。
 *
 * 兼容 Claude Code 的 Agent Skills 目录约定，所以直接读 ~/.claude/skills 就能用现成技能：
 *   <项目>/.mycode/skills/   项目私有技能（优先级最高）
 *   <项目>/.claude/skills/   与 Claude Code 共用的项目技能
 *   <用户>/.claude/skills/   个人技能（Claude Code 装的那些默认放这）
 *   <mycode 安装目录>/skills  随客户端分发
 *
 * 同名技能只保留优先级最高的那份 —— 项目级覆盖个人级，这是 Claude Code 的规则。
 *
 * 渐进披露：启动时只把「名字 + 描述」注入 system（每个约 30~120 字），
 * 模型判断相关时才调 use_skill 把正文读进来。这样装十几个技能也不会撑爆上下文。
 * @author my
 */
public class SkillRegistry {

    /** 描述注入 system 时的截断长度。太长会让清单本身变成负担 */
    private static final int DESC_LIMIT = 120;

    /** 单个技能附带文件的展示上限 */
    private static final int FILE_LIMIT = 24;

    /** 技能名 -> 技能，保持扫描顺序便于展示 */
    private final Map<String, Skill> skills = new LinkedHashMap<>();

    /**
     * 扫描所有位置。
     *
     * @param projectRoot 当前工作目录
     * @return 注册表；目录不存在就返回空表，绝不抛异常（技能是可选能力，不该阻断启动）
     */
    public static SkillRegistry discover(Path projectRoot) {
        SkillRegistry registry = new SkillRegistry();
        Path root = projectRoot.toAbsolutePath().normalize();

        registry.scan(root.resolve(".mycode").resolve("skills"), "项目");
        registry.scan(root.resolve(".claude").resolve("skills"), "项目(Claude)");

        // user.home 在 Windows 上是 C:\Users\<用户名>
        String home = System.getProperty("user.home");
        if (home != null) {
            registry.scan(Path.of(home, ".claude", "skills"), "个人");
        }

        // 随客户端分发的内置技能
        String installDir = System.getProperty("mycode.home");
        if (installDir != null && !installDir.isBlank()) {
            registry.scan(Path.of(installDir, "skills"), "内置");
        }

        registry.applyFilter(AgentConfig.get("skills"));
        return registry;
    }

    /** 扫描一个目录下的「子目录/SKILL.md」；已存在同名技能则跳过（先扫到的优先） */
    private void scan(Path dir, String source) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> candidates = stream.filter(Files::isDirectory).sorted().toList();
            for (Path sub : candidates) {
                Path skillFile = sub.resolve("SKILL.md");
                if (!Files.isRegularFile(skillFile)) continue;
                try {
                    Skill skill = parse(sub, source);
                    if (skill == null) continue;
                    // 同名保留优先级高的那份，但同名时项目级应覆盖个人级，
                    // 所以这里用「已存在就跳过」而不是覆盖 —— 扫描顺序就是优先级顺序
                    skills.putIfAbsent(skill.name(), skill);
                } catch (Exception ignore) {
                    // 单个技能格式不对不影响其它技能
                }
            }
        } catch (IOException ignore) {
            // 目录读不了就算了
        }
    }

    /**
     * 解析一个技能目录。
     *
     * @return 解析失败（缺 SKILL.md 或为空）返回 null
     */
    static Skill parse(Path dir, String source) throws IOException {
        Path file = dir.resolve("SKILL.md");
        // 用 CodeFile 读，顺带支持 GBK 编码的 SKILL.md
        String text = CodeFile.read(file).text();
        if (text.isBlank()) return null;

        Frontmatter fm = Frontmatter.split(text);
        String name = fm.value("name");
        if (name == null || name.isBlank()) {
            // 没写 name 就用目录名，和 Claude Code 的行为一致
            name = dir.getFileName().toString();
        }
        String desc = fm.value("description");
        if (desc == null || desc.isBlank()) desc = "(未提供描述)";

        List<String> allowed = new ArrayList<>();
        String tools = fm.value("allowed-tools");
        if (tools != null && !tools.isBlank()) {
            for (String t : tools.split(",")) {
                if (!t.isBlank()) allowed.add(t.strip());
            }
        }
        return new Skill(name.strip(), desc.strip(), dir, fm.body().strip(), allowed, source);
    }

    /** 按配置里的白名单过滤，例如 skills=docx,xlsx,pptx */
    private void applyFilter(String allowList) {
        if (allowList == null || allowList.isBlank()) return;
        List<String> keep = new ArrayList<>();
        for (String s : allowList.split(",")) {
            if (!s.isBlank()) keep.add(s.strip());
        }
        if (keep.isEmpty()) return;
        skills.keySet().removeIf(name -> !keep.contains(name));
    }

    /** 全部技能，按扫描顺序 */
    public List<Skill> all() {
        return new ArrayList<>(skills.values());
    }

    /** 按名字查找 */
    public Optional<Skill> find(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(skills.get(name.strip()));
    }

    /** 技能个数 */
    public int size() {
        return skills.size();
    }

    /**
     * 生成注入 system 的技能清单。
     * 只放名字和截断后的描述，正文留到 use_skill 时再加载。
     */
    public String catalog() {
        if (skills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n可用技能（处理对应文件类型时先用 use_skill 加载完整步骤）：\n");
        for (Skill s : skills.values()) {
            String desc = s.description().replace("\n", " ");
            if (desc.length() > DESC_LIMIT) desc = desc.substring(0, DESC_LIMIT) + "…";
            sb.append("- ").append(s.name()).append("：").append(desc).append('\n');
        }
        sb.append("当用户的操作对象是 Word/Excel/PPT/PDF 等文件时，必须先 use_skill 取回该技能的完整说明再动手。");
        return sb.toString();
    }

    /**
     * 技能目录下的文件清单，供模型知道有什么脚本可用。
     * 只列相对路径，最多 FILE_LIMIT 条。
     */
    public static List<String> bundledFiles(Skill skill) {
        List<String> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(skill.dir(), 3)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals("SKILL.md"))
                    // 大量缓存/隐藏文件没必要列出来
                    .filter(p -> !p.toString().contains("/.git/") && !p.toString().contains("__pycache__"))
                    .sorted()
                    .limit(FILE_LIMIT)
                    .forEach(p -> out.add(skill.dir().relativize(p).toString().replace('\\', '/')));
        } catch (IOException ignore) {
            // 列不出来就返回空的
        }
        return out;
    }
}
