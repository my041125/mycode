package mycode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * repo map 工具：按字符预算生成代码库大纲 —— Aider「repo map」思路的简化版。
 *
 * 模型定位代码的痛点：list_files 只有文件名，read_code 是带行号全文，
 * 两者之间缺一层「这个库里有哪些类、每个类有哪些方法」。
 * repo_map 补上这一层，模型先看图定位、再精读，省掉好几轮盲猜。
 *
 * Java 提取用正则做启发式（类型声明 + public 方法签名），不引 tree-sitter：
 * 教学项目，准头够用就行，跨行签名这类边角认不出来也不影响导航。
 * 非 Java 文件只进目录树，不提取签名。
 * @author my
 */
public class RepoMapTool implements Tool {

    /** 默认与最大字符预算。约 3k 字一次看完还有富余；预算用完就停，宁可少不可滥 */
    private static final int DEFAULT_BUDGET = 3000;
    private static final int MAX_BUDGET = 8000;

    /** 扫描文件总数上限，防止在巨型仓库上走完全盘 */
    private static final int MAX_FILES = 400;

    /** 不进入大纲的目录名（按路径段匹配） */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "target", "node_modules", ".mycode", ".idea", "downloads", "tools");

    /** Java 类型声明：class / interface / enum / record */
    private static final Pattern TYPE = Pattern.compile(
            "^\\s*(?:public\\s+|protected\\s+|private\\s+)?(?:static\\s+)?"
                    + "(?:final\\s+|abstract\\s+|sealed\\s+)?(class|interface|enum|record)\\s+(\\w+)");

    /** Java public 方法签名（单行启发式，跨行签名认不出就算了） */
    private static final Pattern METHOD = Pattern.compile(
            "^\\s*public\\s+[\\w<>\\[\\],.\\s]+?\\s+(\\w+)\\s*\\([^)]*\\)");

    private final Path root;

    public RepoMapTool(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "repo_map";
    }

    @Override
    public String description() {
        return "生成代码库大纲：目录树 + 每个类的 public 方法签名，按预算截断。"
                + "定位代码先用它，找到目标文件再 read_code 精读。"
                + "可用 path 参数只看某个子目录，budget 调整大纲长度。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Tool.schema(Map.of(
                "path", Tool.str("可选。只生成某个子目录的大纲，相对项目根目录"),
                "budget", Tool.num("可选。大纲字符预算，默认 3000，最大 8000")));
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        // path 可选：给了就只看子目录，同样不允许逃出项目根
        Path base = root;
        String sub = String.valueOf(args.getOrDefault("path", "")).strip();
        if (!sub.isEmpty() && !"null".equals(sub)) {
            base = root.resolve(sub.replace('\\', '/')).normalize();
            if (!base.startsWith(root)) {
                throw new IllegalArgumentException("拒绝访问项目目录之外的路径: " + sub);
            }
        }
        int budget = Math.max(500, Math.min(MAX_BUDGET,
                Tool.toInt(args.get("budget"), DEFAULT_BUDGET)));

        StringBuilder out = new StringBuilder();
        int used = 0;
        int listed = 0;

        for (Path file : listFiles(base)) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            String line = rel.toLowerCase().endsWith(".java")
                    ? rel + ": " + javaOutline(file)
                    : rel;
            if (used + line.length() > budget) {
                out.append("…（已达字符预算 ").append(budget)
                        .append("，剩余文件未列出。可用 path 参数缩小范围）");
                return out.toString();
            }
            out.append(line).append('\n');
            used += line.length() + 1;
            listed++;
        }
        return listed == 0 ? "（没有可列出的文件）" : out.toString().strip();
    }

    /**
     * 列出大纲要覆盖的文件：跳过构建产物与依赖目录，Java 文件排前面
     * （它们才是大纲的主角），路径排序保证输出稳定。
     */
    private List<Path> listFiles(Path base) throws IOException {
        try (Stream<Path> walk = Files.walk(base, 8)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !inSkippedDir(p))
                    .limit(MAX_FILES)
                    .sorted((a, b) -> {
                        boolean ja = a.getFileName().toString().endsWith(".java");
                        boolean jb = b.getFileName().toString().endsWith(".java");
                        if (ja != jb) return ja ? -1 : 1;   // Java 优先
                        return a.compareTo(b);
                    })
                    .toList();
        }
    }

    /** 路径中任一段命中跳过目录即排除 */
    private boolean inSkippedDir(Path p) {
        for (Path part : p) {
            if (SKIP_DIRS.contains(part.toString())) return true;
        }
        return false;
    }

    /** 提取一个 Java 文件的类型名与 public 方法签名，拼成一行摘要 */
    private String javaOutline(Path file) throws IOException {
        String type = "";
        List<String> methods = new ArrayList<>();
        for (String line : CodeFile.read(file).text().split("\n")) {
            Matcher t = TYPE.matcher(line);
            if (t.find() && type.isEmpty()) {
                type = t.group(2);
                continue;
            }
            Matcher m = METHOD.matcher(line);
            if (m.find()) methods.add(m.group(1) + "()");
        }
        if (type.isEmpty()) return "(无类型声明)";
        return methods.isEmpty() ? type : type + " — " + String.join(", ", methods);
    }
}
