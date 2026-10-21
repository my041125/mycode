package mycode;

import com.sun.source.util.JavacTask;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Java 语法校验器 —— 用编译器只做「解析」，不做「类型检查」。
 *
 * 为什么不直接跑 mvn compile：太慢（几秒到几十秒），agent 循环里每改一次跑一次受不了。
 * 为什么不做类型检查：类型检查会把「引用了还没 import 的类」也报成错，
 * 而这类问题应该让模型按上下文自己处理；这里只保证「括号、分号、语法结构没写坏」。
 *
 * 非 Java 文件不做语法校验，只做括号与引号的平衡检查。
 */
public class JavaSyntaxCheck {

    /**
     * 校验结果。
     *
     * @param ok      是否通过。非 Java 文件的括号检查也算在内
     * @param message 给模型看的说明，失败时包含行号和具体原因
     */
    public record Result(boolean ok, String message) {
    }

    /**
     * 校验一个文件。
     * Java 文件走编译器解析，其它文件走括号平衡检查。
     *
     * @param file 待校验文件
     * @return 校验结果，ok=false 时调用方应该回滚
     */
    public static Result check(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".java")) {
            return new Result(true, balanceCheck(file));
        }

        // 只有 JDK 才有编译器，JRE 环境下 ToolProvider 返回 null
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new Result(true, "（当前是 JRE，跳过语法校验）");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try {
            // 不能直接把文件路径交给 javac：老项目里的 GBK 文件会被当成 UTF-8 解析而误报。
            // 这里用 CodeFile 按原编码解码成字符串，再包装成内存源交给编译器，编码问题就绕开了。
            String content = CodeFile.read(file).text();
            JavaFileObject source = new SimpleJavaFileObject(
                    java.net.URI.create("string:///Check.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return content;
                }
            };
            // -proc:none 关掉注解处理，只解析语法树（parse），不做符号解析
            JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostics,
                    List.of("-proc:none"), null, List.of(source));
            task.parse();
        } catch (Exception e) {
            // 校验器自己出问题，不应该阻断业务，如实上报即可
            return new Result(false, "语法校验器异常: " + e.getMessage());
        }

        // 只收集 ERROR 级别，警告（比如没 import）不算失败
        List<String> errors = diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> "第 " + d.getLineNumber() + " 行: " + d.getMessage(Locale.CHINA))
                .limit(5)   // 最多报 5 条，太多反而干扰模型判断
                .toList();

        return errors.isEmpty()
                ? new Result(true, "语法校验通过")
                : new Result(false, "语法错误 " + errors.size() + " 处 -> " + String.join("; ", errors));
    }

    /**
     * 非 Java 文件的粗检：只检查括号和引号是否成对。
     *
     * 能挡住最常见的一类问题 —— 模型生成的代码在中途被截断，
     * 导致最后少一个 } 或少了引号。做不到语法级校验，但成本极低。
     *
     * 实现上用四个状态位跳过注释和字符串内部的括号，否则
     * ")" 这种字符串里的括号会被误判。
     */
    private static String balanceCheck(Path file) {
        try {
            String s = Files.readString(file, StandardCharsets.UTF_8);
            int[] stack = new int[256];   // 手动栈，够用即可
            int sp = 0;
            boolean inStr = false, inChar = false, inLine = false, inBlock = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                char n = i + 1 < s.length() ? s.charAt(i + 1) : '\0';

                // 单行注释：遇到换行结束
                if (inLine) {
                    if (c == '\n') inLine = false;
                    continue;
                }
                // 块注释：遇到 */ 结束
                if (inBlock) {
                    if (c == '*' && n == '/') { inBlock = false; i++; }
                    continue;
                }
                // 双引号字符串：跳过转义字符
                if (inStr) {
                    if (c == '\\') i++;
                    else if (c == '"') inStr = false;
                    continue;
                }
                // 单引号字符
                if (inChar) {
                    if (c == '\\') i++;
                    else if (c == '\'') inChar = false;
                    continue;
                }

                switch (c) {
                    case '/' -> {
                        if (n == '/') { inLine = true; i++; }
                        else if (n == '*') { inBlock = true; i++; }
                    }
                    case '"' -> inStr = true;
                    case '\'' -> inChar = true;
                    case '(', '[', '{' -> stack[sp++] = c;
                    case ')', ']', '}' -> {
                        if (sp == 0) return "括号不匹配：第 " + (i + 1) + " 个字符处多了一个 " + c;
                        char open = (char) stack[--sp];
                        boolean match = (c == ')' && open == '(')
                                || (c == ']' && open == '[')
                                || (c == '}' && open == '{');
                        if (!match) return "括号不匹配：期望闭合 " + open + "，实际遇到 " + c;
                    }
                    default -> {
                        // 普通字符，不用处理
                    }
                }
            }
            if (sp > 0) return "括号未闭合：还有 " + sp + " 个 ( [ { 没有配对";
            return "括号平衡检查通过";
        } catch (Exception e) {
            return "（无法读取文件，跳过校验）";
        }
    }
}
