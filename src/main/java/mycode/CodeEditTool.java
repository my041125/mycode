package mycode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 代码编辑工具 —— 「代码自动填充与智能修改」能力最终落地的地方。
 *
 * 一次成功的编辑要走完 6 步，任何一步失败都回滚：
 *   1. 路径必须落在项目根目录内（防 ../ 逃逸）
 *   2. 读文件，记录编码 / 换行 / BOM
 *   3. 纯文本变换（锚点唯一性、缩进对齐在 CodeEditor 里做）
 *   4. 有改动才落盘，落盘前先备份 .bak
 *   5. Java 文件跑语法校验，非 Java 跑括号平衡检查
 *   6. 校验失败 → 自动还原备份，把错误详情返回给模型让它重试
 *
 * 为什么要把「校验 + 回滚」做进工具而不是交给模型：
 * 模型不会主动验证自己写的东西能不能编译。把校验放在工具里，
 * 改错就会被自动撤销，模型收到 ERROR 后自然会换个写法，这比在提示词里反复强调可靠得多。
 */
public class CodeEditTool implements Tool {

    /** 差异预览最多显示多少行 */
    private static final int PREVIEW_LIMIT = 26;

    /** 项目根目录（绝对路径、已归一化）。所有文件操作都不允许跳出这个范围 */
    private final Path root;

    /**
     * @param root 项目根目录，通常传运行目录
     */
    public CodeEditTool(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** 工具名，模型靠它调用 */
    @Override
    public String name() {
        return "edit_code";
    }

    /**
     * 用途说明。这段文字直接决定模型会不会按规矩改代码，
     * 所以要写得像一份强制规范，而不是功能介绍。
     */
    @Override
    public String description() {
        return """
                修改已有代码文件。支持按锚点插入、替换、删除，或按行号替换，以及追加到文件末尾。
                使用要求（不遵守会直接报错）：
                1. 必须先 read_code 读取原文，anchor 要从原文逐字复制（含缩进），并带足够上下文保证全文件唯一；
                2. 只改需要改的部分，禁止用一次调用重写整个文件；
                3. newCode 不用手动对齐缩进，工具会按目标位置自动重排；
                4. 一次调用只做一件事，多处修改就多次调用。
                操作类型 op：
                  insert_before / insert_after —— 在锚点前/后插入新代码（补全代码片段的场景）
                  replace      —— 用 newCode 替换锚点片段（重构、改逻辑）
                  delete       —— 删除锚点片段
                  replace_lines—— 用 startLine/endLine 指定行号替换（原文对不上时的兜底）
                  append       —— 追加到文件末尾
                """;
    }

    /** 参数定义。每个字段都写了用途，模型据此生成调用参数 */
    @Override
    public Map<String, Object> parameters() {
        return Tool.schema(Map.of(
                "path", Tool.str("相对项目根目录的文件路径，例如 src/main/java/mycode/Main.java"),
                "op", Tool.str("操作类型: insert_before / insert_after / replace / delete / replace_lines / append"),
                "anchor", Tool.str("锚点片段，从原文逐字复制，必须全文件唯一。append 时可省略"),
                "newCode", Tool.str("要插入或替换的新代码，不含最外层公共缩进"),
                "startLine", Tool.num("replace_lines 的起始行号（从 1 开始）"),
                "endLine", Tool.num("replace_lines 的结束行号，与 startLine 相同可省略"),
                "allowLarge", Tool.str("改动超过 150 行时必须显式传 true")),
                "path", "op");
    }

    /**
     * 执行一次代码修改。
     *
     * 返回给模型的文本包含五段信息：改了哪个文件、改了啥、编码换行、校验结果、差异预览。
     * 差异预览很重要 —— 模型能据此确认自己改对了位置，用户也能一眼看出改动范围。
     */
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        // 1. 解析并校验路径
        Path file = resolve(String.valueOf(args.getOrDefault("path", "")));
        String op = String.valueOf(args.getOrDefault("op", "")).strip();

        // 2. 读文件，拿到内容加隐形属性（编码、换行、BOM）
        CodeFile codeFile = CodeFile.read(file);
        CodeEditor.Edit edit = new CodeEditor.Edit(
                op,
                args.get("anchor") == null ? null : String.valueOf(args.get("anchor")),
                args.get("newCode") == null ? null : String.valueOf(args.get("newCode")),
                asInt(args.get("startLine")),
                asInt(args.get("endLine")),
                Boolean.parseBoolean(String.valueOf(args.getOrDefault("allowLarge", "false"))));

        // 3. 纯文本变换。锚点不唯一、找不到等情况会在这里抛异常，由 Registry 兜住
        CodeEditor.Result result = CodeEditor.apply(codeFile.text(), edit);
        if (result.newText().equals(codeFile.text())) {
            // 没产生差异通常是模型给的新旧代码一样，让模型自己检查，而不是当作成功
            return "未产生任何改动（新代码与原文一致），请检查 anchor 是否选对、newCode 是否为空";
        }

        // 4. 先备份再落盘
        Path backup = codeFile.backup();
        codeFile.write(result.newText());

        // 5. 校验。Java 走编译器解析，其它语言走括号平衡
        JavaSyntaxCheck.Result check = JavaSyntaxCheck.check(file);
        if (!check.ok()) {
            // 6. 校验失败立即回滚，保证磁盘上永远是「至少能编译」的版本
            Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING);
            return "ERROR: 修改后校验失败，已自动回滚到修改前状态。原因：" + check.message()
                    + "\n请修正后重新提交本次修改。";
        }

        // 7. 生成差异预览（带两行上下文），过长就截断，别把上下文占满
        List<String> preview = CodeEditor.Diff.preview(codeFile.text(), result.newText(), 2);
        if (preview.size() > PREVIEW_LIMIT) {
            preview = new ArrayList<>(preview.subList(0, PREVIEW_LIMIT));
            preview.add("…（差异过长，仅显示前 26 行）");
        }

        String rel = root.relativize(file).toString().replace('\\', '/');
        return "已修改 " + rel + "\n"
                + "操作: " + op + " | 影响起始行: " + result.startLine() + " | " + result.summary()
                + " | 编码: " + codeFile.charsetName() + " | 换行: "
                + ("\r\n".equals(codeFile.eol()) ? "CRLF" : "LF") + "\n"
                + "校验: " + check.message() + "\n"
                + "备份: " + backup.getFileName() + "\n"
                + "差异:\n" + String.join("\n", preview);
    }

    /**
     * 把模型给的相对路径解析成绝对路径，并确保没有逃出项目目录。
     *
     * 这一步不能省：模型完全可能给出 ../../Windows/System32 这种路径，
     * 一旦放行，一个「帮你改代码」的工具就变成了任意文件写入漏洞。
     */
    private Path resolve(String raw) {
        String cleaned = raw.strip().replace('\\', '/');
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        Path p = root.resolve(cleaned).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("拒绝访问项目目录之外的路径: " + raw);
        }
        return p;
    }

    /**
     * 把行号参数转成 Integer。
     * 模型可能传数字、传小数、传字符串，统一兼容；解析不了就返回 null 交给上层判断。
     */
    private Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return (int) Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
