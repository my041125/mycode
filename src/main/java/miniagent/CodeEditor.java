package miniagent;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯函数式代码编辑器：不碰磁盘，只做文本变换，方便单测。
 *
 * 支持的 6 种操作，覆盖「自动补全」和「智能修改」两个场景：
 *   insert_before / insert_after   —— 按锚点插入（补全代码片段）
 *   replace                        —— 按锚点整段替换（重构）
 *   delete                         —— 按锚点删除
 *   replace_lines                  —— 按行号替换（模型拿不准原文时用）
 *   append                         —— 追加到文件末尾
 *
 * 一条铁律：锚点必须在文件里唯一。锚点不唯一说明模型给的上下文不够，
 * 这时宁可报错让它重新读文件，也不要猜一个位置改下去 —— 猜错就是灾难。
 */
public class CodeEditor {

    /**
     * 一次编辑指令。
     *
     * @param op         操作类型，见类注释里列出的 6 种
     * @param anchor     锚点原文，必须全文件唯一；append 时可省略
     * @param newCode    新代码，不需要带最外层公共缩进
     * @param startLine  replace_lines 的起始行号（1 起）
     * @param endLine    replace_lines 的结束行号，null 表示与 startLine 相同
     * @param allowLarge 是否允许超过 150 行的大改动
     */
    public record Edit(String op, String anchor, String newCode,
                       Integer startLine, Integer endLine, boolean allowLarge) {
    }

    /**
     * 编辑结果。
     *
     * @param newText   修改后的完整文本
     * @param startLine 受影响的起始行号
     * @param endLine   受影响的结束行号
     * @param summary   增删行数摘要，例如 "+3 / -2"
     */
    public record Result(String newText, int startLine, int endLine, String summary) {
    }

    /**
     * 执行编辑：解析操作类型 → 定位锚点 → 对齐缩进 → 生成新文本 → 安全检查。
     *
     * @param original 原始全文（已统一成 \n 换行）
     * @param e        编辑指令
     * @return 新文本与受影响行号
     * @throws IllegalArgumentException 锚点找不到 / 不唯一 / 改动过大时抛出，由 Registry 转成 ERROR 文本
     */
    public static Result apply(String original, Edit e) {
        String src = original.replace("\r\n", "\n");
        int totalLines = src.split("\n", -1).length;

        String op = e.op() == null ? "" : e.op().strip().toLowerCase();
        String updated;
        int touchedFrom;
        int touchedTo;

        switch (op) {
            case "insert_before", "insert_after", "replace", "delete" -> {
                String anchor = e.anchor();
                if (anchor == null || anchor.isEmpty()) {
                    throw new IllegalArgumentException("op=" + op + " 必须提供 anchor（从原文逐字复制的片段）");
                }
                if (anchor.indexOf('\n') < 0 && anchor.strip().length() < 3) {
                    throw new IllegalArgumentException(
                            "anchor 太短容易误伤。请把上一行（或下一行）一起带上，让它能唯一定位");
                }
                anchor = anchor.replace("\r\n", "\n");
                int idx = uniqueIndex(src, anchor);
                int fromLine = lineOf(src, idx);
                int toLine = lineOf(src, idx + anchor.length());

                String indent = baseIndent(src, fromLine, anchor);
                String block = e.newCode() == null ? "" : reindent(e.newCode(), indent);

                updated = switch (op) {
                    case "insert_before" -> {
                        String head = src.substring(0, idx);
                        String tail = src.substring(idx);
                        String prefix = block;
                        // 插入块后面紧跟内容时补换行，否则会和锚点行粘成一行
                        if (!prefix.isEmpty() && !prefix.endsWith("\n") && !tail.startsWith("\n")) {
                            prefix = prefix + "\n";
                        }
                        yield head + prefix + tail;
                    }
                    case "insert_after" -> {
                        int end = idx + anchor.length();
                        String head = src.substring(0, end);
                        String tail = src.substring(end);
                        String prefix = block;
                        // 锚点后面不是换行（例如插在行尾），必须先补一个换行，否则会和下一行粘在一起
                        if (!prefix.isEmpty() && !head.endsWith("\n") && !prefix.startsWith("\n")) {
                            prefix = "\n" + prefix;
                        }
                        // 锚点后面紧接内容，补一个换行避免粘连
                        if (!prefix.isEmpty() && !prefix.endsWith("\n")
                                && !tail.isEmpty() && !tail.startsWith("\n")) {
                            prefix = prefix + "\n";
                        }
                        yield head + prefix + tail;
                    }
                    case "replace" -> src.substring(0, idx) + block + src.substring(idx + anchor.length());
                    default -> cleanDelete(src, idx, idx + anchor.length());
                };
                touchedFrom = fromLine;
                touchedTo = op.equals("delete") ? fromLine : lineOf(updated, idx);
            }
            case "replace_lines" -> {
                Integer s = e.startLine();
                Integer t = e.endLine() == null ? e.startLine() : e.endLine();
                if (s == null || s < 1 || t < s || t > totalLines) {
                    throw new IllegalArgumentException("行号越界：文件共 " + totalLines + " 行，收到 "
                            + s + "~" + t);
                }
                List<String> lines = new ArrayList<>(List.of(src.split("\n", -1)));
                String indent = indentOf(src, s);
                String block = e.newCode() == null ? "" : reindent(e.newCode(), indent);
                List<String> replacement = block.isEmpty()
                        ? List.of()
                        : new ArrayList<>(List.of(block.split("\n", -1)));
                // 末尾空行属于块外，去掉避免多出空行
                if (!replacement.isEmpty() && replacement.get(replacement.size() - 1).isEmpty()
                        && block.endsWith("\n")) {
                    replacement.remove(replacement.size() - 1);
                }
                lines.subList(s - 1, t).clear();
                lines.addAll(s - 1, replacement);
                updated = String.join("\n", lines);
                touchedFrom = s;
                touchedTo = s + Math.max(0, replacement.size() - 1);
            }
            case "append" -> {
                String block = e.newCode() == null ? "" : e.newCode();
                updated = src.endsWith("\n") || src.isEmpty() ? src + block : src + "\n" + block;
                touchedFrom = totalLines;
                touchedTo = totalLines + block.split("\n", -1).length;
            }
            default -> throw new IllegalArgumentException(
                    "不支持的操作: " + op + "。可用: insert_before / insert_after / replace / "
                            + "delete / replace_lines / append");
        }

        Diff d = Diff.of(src, updated);
        if (!e.allowLarge() && d.changedLines() > 150) {
            throw new IllegalArgumentException("本次改动 " + d.changedLines()
                    + " 行，超过安全阈值 150 行，已拒绝。若确实要整段重构，请加 allowLarge=true");
        }
        return new Result(updated, touchedFrom, touchedTo,
                "改动行数 +" + d.added() + " / -" + d.removed());
    }

    // ---------------- 锚点相关 ----------------

    /**
     * 在 src 里定位锚点，并要求全文件唯一。
     *
     * 找不到时给出可执行的建议（重新读文件），而不是一句「没找到」；
     * 找到多处时把两个行号都报出来，模型据此补足上下文就能定位唯一位置。
     *
     * @return 锚点在文本中的起始下标
     */
    private static int uniqueIndex(String src, String anchor) {
        int first = src.indexOf(anchor);
        if (first < 0) {
            throw new IllegalArgumentException("锚点未找到。可能原因：缩进不一致、多/少空格、"
                    + "或文件已被修改。请用 read_code 重新读取原文后再试");
        }
        int second = src.indexOf(anchor, first + 1);
        if (second >= 0) {
            throw new IllegalArgumentException("锚点不唯一，出现在第 " + lineOf(src, first)
                    + " 行和第 " + lineOf(src, second) + " 行。请多复制几行上下文让它唯一");
        }
        return first;
    }

    /** 根据字符下标算出它在第几行（从 1 开始），用于报错和差异预览 */
    private static int lineOf(String src, int index) {
        int line = 1;
        for (int i = 0; i < index && i < src.length(); i++) {
            if (src.charAt(i) == '\n') line++;
        }
        return line;
    }

    /** 取某一行的前导空白（缩进），用于把新代码对齐到该层级 */
    private static String indentOf(String src, int line) {
        String[] lines = src.split("\n", -1);
        if (line - 1 >= lines.length) return "";
        String l = lines[line - 1];
        int i = 0;
        while (i < l.length() && (l.charAt(i) == ' ' || l.charAt(i) == '\t')) i++;
        return l.substring(0, i);
    }

    /**
     * 推断插入代码该用几级缩进 —— 只按锚点行自身的缩进会错。
     *
     * 反例：锚点是收尾的 "}"，它自己缩进是 0，但插进去的代码应该在方法体内（4 空格）。
     * 所以按锚点的「首字符」和「尾字符」判断上下文层级：
     *   锚点以 } ) ] 开头           -> 插在收尾括号前，层级要 +1
     *   锚点以 { 或 ( 结尾           -> 插在开括号后，层级要 +1
     *   其余情况                     -> 与锚点同层
     */
    private static String baseIndent(String src, int line, String anchor) {
        String indent = indentOf(src, line);
        String[] anchorLines = anchor.split("\n");
        String first = anchorLines[0].strip();
        String last = anchorLines[anchorLines.length - 1].strip();

        boolean beforeClosing = first.startsWith("}") || first.startsWith(")")
                || first.startsWith("]");
        boolean afterOpening = last.endsWith("{") || last.endsWith("(");

        return (beforeClosing || afterOpening) ? indent + indentUnit(src) : indent;
    }

    /** 探测文件用的是 Tab 还是几个空格，默认 4 空格 */
    private static String indentUnit(String src) {
        String[] lines = src.split("\n", -1);
        boolean tab = false;
        int min = Integer.MAX_VALUE;
        int prev = 0;
        for (String l : lines) {
            if (l.isBlank()) continue;
            int i = 0;
            while (i < l.length() && (l.charAt(i) == ' ' || l.charAt(i) == '\t')) i++;
            String ind = l.substring(0, i);
            if (ind.indexOf('\t') >= 0) tab = true;
            if (i > prev) min = Math.min(min, i - prev);
            prev = i;
        }
        if (tab) return "\t";
        if (min == Integer.MAX_VALUE || min <= 0 || min > 8) min = 4;
        return " ".repeat(min);
    }

    /**
     * 关键一步：把模型给的代码块重新对齐到目标位置的缩进。
     * 做法是先算块内最小公共缩进，整体剥离，再统一加上目标缩进 ——
     * 这样模型多给或少给缩进都不会破坏文件风格。
     */
    public static String reindent(String code, String targetIndent) {
        String[] lines = code.replace("\r\n", "\n").split("\n", -1);
        int min = Integer.MAX_VALUE;
        for (String l : lines) {
            if (l.isBlank()) continue;
            int i = 0;
            while (i < l.length() && (l.charAt(i) == ' ' || l.charAt(i) == '\t')) i++;
            min = Math.min(min, i);
        }
        if (min == Integer.MAX_VALUE) min = 0;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.isBlank()) {
                sb.append("");
            } else {
                sb.append(targetIndent).append(l.substring(min));
            }
            if (i < lines.length - 1) sb.append('\n');
        }
        return sb.toString();
    }

    /** 删除后如果留下空行，把整行一起吃掉，避免产生无意义的空行 diff */
    private static String cleanDelete(String src, int from, int to) {
        int lineStart = src.lastIndexOf('\n', from - 1) + 1;
        int lineEnd = src.indexOf('\n', to);
        boolean onlySpacesBefore = src.substring(lineStart, from).isBlank();
        boolean restIsSpaces = lineEnd < 0 || src.substring(to, lineEnd).isBlank();
        if (onlySpacesBefore && restIsSpaces) {
            int cut = lineEnd < 0 ? src.length() : lineEnd + 1;
            return src.substring(0, lineStart) + src.substring(cut);
        }
        return src.substring(0, from) + src.substring(to);
    }

    // ---------------- 差异统计 ----------------

    public record Diff(int added, int removed) {

        /** 新增行数 + 删除行数，用来判断改动规模是否异常 */
        int changedLines() {
            return added + removed;
        }

        /**
         * 用最长公共子序列（LCS）算「新增多少行、删除多少行」。
         *
         * 为什么不用简单比对行数：改一行文字，行数不变，但实际是 +1/-1。
         * LCS 能给出准确的增删行数，也顺便用来判断「这次算不算大改动」。
         */
        static Diff of(String a, String b) {
            String[] x = a.split("\n", -1);
            String[] y = b.split("\n", -1);
            // 大文件用 LCS 太占内存，退化成长度估算
            if ((long) x.length * y.length > 4_000_000L) {
                int d = Math.max(0, y.length - x.length);
                return new Diff(d, Math.max(0, x.length - y.length));
            }
            int[][] dp = new int[x.length + 1][y.length + 1];
            for (int i = x.length - 1; i >= 0; i--) {
                for (int j = y.length - 1; j >= 0; j--) {
                    dp[i][j] = x[i].equals(y[j])
                            ? dp[i + 1][j + 1] + 1
                            : Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
            int lcs = dp[0][0];
            return new Diff(y.length - lcs, x.length - lcs);
        }

        /** 生成可读的差异片段，直接给模型和用户看 */
        public static List<String> preview(String a, String b, int context) {
            String[] x = a.split("\n", -1);
            String[] y = b.split("\n", -1);
            int pre = 0;
            while (pre < x.length && pre < y.length && x[pre].equals(y[pre])) pre++;
            int suf = 0;
            while (suf < x.length - pre && suf < y.length - pre
                    && x[x.length - 1 - suf].equals(y[y.length - 1 - suf])) suf++;

            List<String> out = new ArrayList<>();
            out.add("@@ 第 " + Math.max(1, pre - context + 1) + " 行起 @@");
            for (int i = Math.max(0, pre - context); i < pre; i++) {
                out.add("  " + x[i]);
            }
            for (int i = pre; i < x.length - suf; i++) {
                out.add("- " + x[i]);
            }
            for (int i = pre; i < y.length - suf; i++) {
                out.add("+ " + y[i]);
            }
            int end = Math.min(x.length, x.length - suf + context);
            for (int i = x.length - suf; i < end; i++) {
                out.add("  " + x[i]);
            }
            return out;
        }
    }
}
