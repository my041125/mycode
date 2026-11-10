package mycode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 只读类代码工具：read_code、list_files。
 *
 * 为什么必须给模型配 read_code：模型看不到磁盘，只能靠工具把原文喂进上下文。
 * 没有它，agent 就会凭「记忆」编造锚点，然后一次都改不对。
 * 这也是代码类 agent 和聊天类 agent 最本质的区别。
 * @author my
 */
public class CodeTools {

    /** 单次最多返回的行数，防止一个 5000 行的文件直接把上下文占满 */
    private static final int MAX_READ_LINES = 400;

    /** 目录遍历最多返回的文件数 */
    private static final int MAX_LIST_FILES = 200;

    /** 目录遍历的最大深度，防止在 node_modules 这类深目录里翻太久 */
    private static final int MAX_DEPTH = 12;

    /**
     * 构造 read_code 工具。
     *
     * @param root 项目根目录，所有路径都必须落在它内部（防 ../ 逃逸）
     */
    public static Tool readTool(Path root) {
        Path base = root.toAbsolutePath().normalize();
        return new SimpleTool(
                "read_code",
                "读取代码文件的指定行范围，返回带行号的原文。修改代码之前必须先调用它拿到准确原文。"
                        + "默认返回前 400 行，文件较长时用 startLine/endLine 分段读。",
                Tool.schema(Map.of(
                        "path", Tool.str("相对项目根目录的文件路径"),
                        "startLine", Tool.num("起始行号，从 1 开始，默认 1"),
                        "endLine", Tool.num("结束行号，默认 startLine+399")),
                        "path"),
                a -> {
                    // 1. 路径归一化并校验：
                    //    项目目录内随便读；技能目录（项目外）也放行 ——
                    //    否则模型想读 SKILL.md 会被拒，只能退回 cat/sed，白白浪费好几步
                    Path f = base.resolve(String.valueOf(a.get("path")).replace('\\', '/')).normalize();
                    if (!f.startsWith(base) && !isSkillPath(f)) {
                        throw new IllegalArgumentException("拒绝访问项目目录之外的路径: " + a.get("path"));
                    }
                    if (!Files.isRegularFile(f)) throw new IllegalArgumentException("文件不存在: " + a.get("path"));

                    // 2. 计算要读的行范围，越界要明确报错而不是静默返回空
                    // 不能用 Files.readAllLines：它固定按 UTF-8 严格解码，
                    // 遇到 GBK 编码的老项目文件会直接抛 MalformedInputException。
                    // 交给 CodeFile 做编码识别，和写文件时保持同一套判断。
                    List<String> lines = CodeFile.read(f).text().lines().toList();
                    int from = Math.max(1, Tool.toInt(a.get("startLine"), 1));
                    int to = a.get("endLine") == null
                            ? from + MAX_READ_LINES - 1
                            : Math.min(lines.size(), Tool.toInt(a.get("endLine"), 1));
                    if (from > lines.size()) {
                        throw new IllegalArgumentException("起始行超出文件长度，文件共 " + lines.size() + " 行");
                    }
                    to = Math.min(to, lines.size());

                    // 3. 带行号输出。行号很重要：模型按行号定位比按文本定位更稳
                    StringBuilder sb = new StringBuilder();
                    sb.append(f.getFileName()).append(" 共 ").append(lines.size())
                            .append(" 行，以下为第 ").append(from).append("~").append(to).append(" 行：\n");
                    for (int i = from; i <= to; i++) {
                        sb.append(String.format("%4d| ", i)).append(lines.get(i - 1)).append('\n');
                    }
                    // 4. 没读完要告诉模型怎么继续，否则它会以为文件就这么长
                    if (to < lines.size()) {
                        sb.append("…（未读完，剩余 ").append(lines.size() - to)
                                .append(" 行，需要时用 startLine=").append(to + 1).append(" 继续）");
                    }
                    return sb.toString();
                });
    }

    /**
     * 判断路径是否落在技能目录内。
     *
     * 技能装在 ~/.claude/skills（项目外），但读它是安全的：
     * 里面只有 SKILL.md 和配套脚本。放行可以避免模型为了看一眼技能正文
     * 而绕道 run_command 用 cat/sed —— 那既费步数，也绕过了读文件的保护逻辑。
     */
    private static boolean isSkillPath(Path f) {
        List<Path> roots = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (home != null) {
            roots.add(Path.of(home, ".claude", "skills").toAbsolutePath().normalize());
        }
        String install = System.getProperty("mycode.home");
        if (install != null && !install.isBlank()) {
            roots.add(Path.of(install, "skills").toAbsolutePath().normalize());
        }
        for (Path r : roots) {
            if (f.startsWith(r)) return true;
        }
        return false;
    }

    /**
     * 构造 list_files 工具，用于定位要改哪个文件。
     *
     * @param root 项目根目录
     */
    public static Tool listTool(Path root) {
        Path base = root.toAbsolutePath().normalize();
        return new SimpleTool(
                "list_files",
                "列出项目目录下的代码文件，用于定位要修改的文件。会跳过 target、.git、.idea 等目录。",
                Tool.schema(Map.of(
                        "dir", Tool.str("相对项目根目录的子目录，默认 ."),
                        "keyword", Tool.str("可选，文件名包含的关键字过滤，例如 Ticket")),
                        "dir"),
                a -> {
                    String dirArg = a.get("dir") == null ? "." : String.valueOf(a.get("dir"));
                    Path d = base.resolve(dirArg).normalize();
                    if (!d.startsWith(base)) throw new IllegalArgumentException("拒绝访问项目目录之外的路径");
                    if (!Files.isDirectory(d)) throw new IllegalArgumentException("目录不存在: " + dirArg);

                    String keyword = a.get("keyword") == null ? null : String.valueOf(a.get("keyword"));
                    List<String> out = new ArrayList<>();
                    try (Stream<Path> walk = Files.walk(d, MAX_DEPTH)) {
                        // 过滤掉编译产物、版本库目录和备份文件，否则列表会被垃圾文件淹没
                        List<Path> files = walk.filter(Files::isRegularFile)
                                .filter(p -> {
                                    String s = p.toString().replace('\\', '/');
                                    return !s.contains("/target/") && !s.contains("/.git/")
                                            && !s.contains("/.idea/") && !s.endsWith(".bak")
                                            && !s.endsWith(".class");
                                })
                                .filter(p -> keyword == null || p.getFileName().toString().contains(keyword))
                                .sorted(Comparator.comparing(Path::toString))
                                .limit(MAX_LIST_FILES)
                                .toList();
                        // 统一输出相对路径，模型后续用这个路径调用 read_code 才不会出错
                        for (Path p : files) {
                            out.add(base.relativize(p).toString().replace('\\', '/'));
                        }
                    }
                    if (out.isEmpty()) {
                        return "目录 " + dirArg + " 下没有匹配的代码文件";
                    }
                    return "共 " + out.size() + " 个文件：\n" + String.join("\n", out);
                });
    }

    /** 工具类，禁止实例化 */
    private CodeTools() {
    }
}
