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
 * 子代理注册表：扫描磁盘上的代理定义（.md），提供「清单」和「按名查找」。
 *
 * 目录约定与技能（SkillRegistry）同构，并直接兼容 Claude Code 的子代理目录，
 * 用户给 Claude Code 写过的定义拿来就能用：
 *   <项目>/.mycode/agents/*.md   项目私有（优先级最高）
 *   <项目>/.claude/agents/*.md   与 Claude Code 共用
 *   <用户>/.claude/agents/*.md   个人级（Claude Code 的子代理默认放这）
 *   <mycode 安装目录>/agents      随客户端分发
 *
 * 与技能目录的差异：技能是「子目录/SKILL.md」，子代理是「一层目录下每个代理一个 .md」
 * —— 这是 Claude Code 的既有约定，保持一致才能直接共用文件。
 *
 * 同名定义先扫到的优先（扫描顺序就是优先级顺序），项目级覆盖个人级，规则与技能一致。
 * 无论扫到多少个，最后都保证有一个内置的 general 兜底：
 * 用户一个定义都没写时 task 工具也能开箱即用；写了同名 general 则覆盖内置版。
 * @author my
 */
public class AgentRegistry {

    /** 描述注入 system 时的截断长度，与技能清单同规则 */
    private static final int DESC_LIMIT = 120;

    /** 定义名 -> 定义，保持扫描顺序便于展示 */
    private final Map<String, AgentDefinition> defs = new LinkedHashMap<>();

    /**
     * 扫描所有位置。
     *
     * @param projectRoot 当前工作目录
     * @return 注册表；目录不存在也至少含内置 general，绝不抛异常
     *         （子代理是可选能力，不该阻断启动）
     */
    public static AgentRegistry discover(Path projectRoot) {
        AgentRegistry registry = new AgentRegistry();
        Path root = projectRoot.toAbsolutePath().normalize();

        registry.scan(root.resolve(".mycode").resolve("agents"), "项目");
        registry.scan(root.resolve(".claude").resolve("agents"), "项目(Claude)");

        // user.home 在 Windows 上是 C:\Users\<用户名>
        String home = System.getProperty("user.home");
        if (home != null) {
            registry.scan(Path.of(home, ".claude", "agents"), "个人");
        }

        // 随客户端分发的内置定义
        String installDir = System.getProperty("mycode.home");
        if (installDir != null && !installDir.isBlank()) {
            registry.scan(Path.of(installDir, "agents"), "内置");
        }

        registry.defs.putIfAbsent("general", defaultGeneral());
        return registry;
    }

    /** 扫描一个目录下的 *.md，每个文件是一个代理定义；同名保留先扫到的 */
    private void scan(Path dir, String source) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                try {
                    AgentDefinition def = parse(file, source);
                    if (def != null) defs.putIfAbsent(def.name(), def);
                } catch (Exception ignore) {
                    // 单个定义格式不对不影响其它定义
                }
            }
        } catch (IOException ignore) {
            // 目录读不了就算了
        }
    }

    /**
     * 解析一个定义文件。
     *
     * @return 解析失败（空文件或没有正文）返回 null —— 没有正文的定义没有
     *         system prompt，派出去也没法干活
     */
    static AgentDefinition parse(Path file, String source) throws IOException {
        // 用 CodeFile 读，顺带支持 GBK 编码的定义文件
        String text = CodeFile.read(file).text();
        if (text.isBlank()) return null;

        Frontmatter fm = Frontmatter.split(text);
        String name = fm.value("name");
        if (name == null || name.isBlank()) {
            // 没写 name 就用文件名（去 .md 后缀），和 Claude Code 的行为一致
            String fileName = file.getFileName().toString();
            name = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
        }
        String desc = fm.value("description");
        if (desc == null || desc.isBlank()) desc = "(未提供描述)";

        List<String> allowed = new ArrayList<>();
        String tools = fm.value("tools");
        if (tools != null && !tools.isBlank()) {
            for (String t : tools.split(",")) {
                if (!t.isBlank()) allowed.add(t.strip());
            }
        }

        String body = fm.body().strip();
        if (body.isEmpty()) return null;
        return new AgentDefinition(name.strip(), desc.strip(), body, allowed, source);
    }

    /**
     * 内置的通用子代理：没有定义文件时的兜底。
     * 用户在 agents 目录放一个同名 general.md 即可覆盖它。
     */
    private static AgentDefinition defaultGeneral() {
        return new AgentDefinition(
                "general",
                "通用子代理：独立完成调研、搜索、批量检查等自包含任务，只报告结论与证据",
                """
                        你是通用子代理，独立完成主代理派发给你的任务。
                        工作方式：先弄清任务要什么交付物，再动手；需要了解现状时用工具去查，不要靠猜。
                        完成后给出结论，附上关键证据（文件路径、命令输出、数据）。
                        """,
                List.of(),
                "内置");
    }

    /** 按名查找。task 工具拿模型填的类型名来这里查，查不到由调用方兜底 */
    public Optional<AgentDefinition> find(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(defs.get(name.strip()));
    }

    /** 内置兜底定义。模型填了不存在的类型名时用它，而不是报错打断任务 */
    public AgentDefinition general() {
        return defs.get("general");
    }

    /** 全部定义，按扫描顺序 */
    public List<AgentDefinition> all() {
        return new ArrayList<>(defs.values());
    }

    /** 定义个数 */
    public int size() {
        return defs.size();
    }

    /**
     * 生成注入 system 的子代理清单，附派发原则。
     * 这段文字是「自动拆分长任务」的主要推手：模型靠它判断什么时候该派、派给谁。
     */
    public String catalog() {
        StringBuilder sb = new StringBuilder("\n\n可用子代理（用 task 工具派发）：\n");
        for (AgentDefinition d : defs.values()) {
            String desc = d.description().replace("\n", " ");
            if (desc.length() > DESC_LIMIT) desc = desc.substring(0, DESC_LIMIT) + "…";
            sb.append("- ").append(d.name()).append("：").append(desc).append('\n');
        }
        sb.append("""
                派发原则：可以拆开的长任务（多文件调研、批量同类操作、互不依赖的子问题）\
                主动拆给子代理执行；一两次工具调用就能完成的小事自己做，不要为拆而拆。\
                prompt 必须自包含：目标、涉及文件、期望交付物一次说清 —— 子代理看不到你们的对话历史，\
                也感知不到它被派发之后发生的任何事。拿到结果后先核对关键结论，再决定采信或重派。""");
        return sb.toString();
    }
}
