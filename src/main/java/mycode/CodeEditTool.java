package mycode;

import java.nio.file.Path;
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
 * @author my
 */
public class CodeEditTool implements Tool {

    /** 差异预览最多显示多少行 */
    private static final int PREVIEW_LIMIT = 26;

    /** 验证报告最多保留多少行。编译报错可能几十屏，模型只需要前段的错误定位 */
    private static final int VERIFY_REPORT_LINES = 20;

    /** 项目根目录（绝对路径、已归一化）。所有文件操作都不允许跳出这个范围 */
    private final Path root;

    /** 多版本备份仓库，每次修改前存一份快照，改坏了可以逐步回退 */
    private final BackupStore backups;

    /** 命令执行器。验证命令复用它，危险特征检查（CommandPolicy）随之自动生效 */
    private final CommandTool runner;

    /**
     * 编辑成功后自动执行的验证命令，来自 agent.properties 的 verifyCommand
     * （例如 mvn -q compile）；未配置表示关闭。
     */
    private final String verifyCommand;

    /**
     * @param root 项目根目录，通常传运行目录
     */
    public CodeEditTool(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.backups = new BackupStore(this.root);
        this.runner = new CommandTool(this.root);
        this.verifyCommand = AgentConfig.get("verifyCommand");
    }

    /**
     * 结果字符额度放大到 4000：edit_code 的 observation 含差异预览和验证报告，
     * 全局默认的 1500 字会把最关键的验证报错截掉，模型反而看不到失败原因。
     */
    @Override
    public int resultCharLimit() {
        return 4000;
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

        // 4. 先存快照再落盘。快照是多版本的，所以连改多次也能一层层退回去
        Path snapshot = backups.save(file);
        codeFile.write(result.newText());

        // 5. 校验。Java 走编译器解析，其它语言走括号平衡
        JavaSyntaxCheck.Result check = JavaSyntaxCheck.check(file);
        if (!check.ok()) {
            // 6. 校验失败立即回滚。用 restore 而不是手工拷贝，
            //    是为了顺带把这个「编译不过的版本」从历史里消耗掉 ——
            //    否则用户按 undo 会先退到一个坏版本上，体验很糟
            backups.restore(file);
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
        String observation = "已修改 " + rel + "\n"
                + "操作: " + op + " | 影响起始行: " + result.startLine() + " | " + result.summary()
                + " | 编码: " + codeFile.charsetName() + " | 换行: "
                + ("\r\n".equals(codeFile.eol()) ? "CRLF" : "LF") + "\n"
                + "校验: " + check.message() + "\n"
                + "快照: " + root.relativize(snapshot).toString().replace('\\', '/') + "\n"
                + "差异:\n" + String.join("\n", preview);
        return verify(observation);
    }

    /**
     * 编辑成功后自动跑一次验证命令（agent.properties 的 verifyCommand）。
     *
     * 这是把「改完要验证」从提示词约定变成机制（Aider 的核心工作流）：
     * 编译失败会直接拼进本次 observation，模型下一轮立刻看到报错去修，
     * 不用等它自己想起来调 run_command。
     *
     * 命令只来自用户配置（agent.properties），模型无法指定或修改；
     * 且经由 CommandTool 执行，CommandPolicy 的危险特征检查自动生效。
     * 成功时只回一行，报错时带回输出 —— 撑爆 observation 没有意义。
     */
    private String verify(String resultText) {
        if (verifyCommand == null) return resultText;
        try {
            String report = runner.execute(Map.of(
                    "command", verifyCommand, "timeoutSeconds", 120));
            if (report.contains("退出码: 0 ")) {
                return resultText + "\n[verify] " + verifyCommand + " 通过";
            }
            return resultText + "\n[verify] 失败，输出如下：\n" + head(report, VERIFY_REPORT_LINES);
        } catch (Exception e) {
            return resultText + "\n[verify] 验证命令执行失败: " + e.getMessage();
        }
    }

    /** 取前 n 行。验证报错的定位信息几乎都在开头 */
    private static String head(String text, int n) {
        String[] lines = text.split("\n", -1);
        if (lines.length <= n) return text;
        return String.join("\n", java.util.Arrays.copyOfRange(lines, 0, n))
                + "\n…（后续省略）";
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
