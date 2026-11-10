package mycode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 一个代码文件的内存映像。
 *
 * 只做一件事：把「文件里那些肉眼看不见、但改错了就会产生大量无意义 diff 的东西」原样保住：
 *   - 编码（UTF-8 / GBK，老项目里 GBK 的 Java 文件很常见）
 *   - 换行符（CRLF / LF，Windows 项目几乎都是 CRLF）
 *   - 是否带 BOM
 *   - 文件末尾有没有换行
 *
 * 内部统一用 \n 处理，写回时再还原。少做一步，git diff 里就会出现整文件变更，
 * 代码评审时根本看不出你实际改了什么。
 * @author my
 */
public class CodeFile {

    /** 文件路径 */
    public final Path path;

    /** 原文件编码。写回时必须用它，否则含中文的文件会被写成乱码 */
    private final Charset charset;

    /** 换行符，"\r\n" 或 "\n" */
    private final String eol;

    /** 是否带 UTF-8 BOM。带 BOM 的文件被某些编译器当非法字符，不能随便加也不能随便删 */
    private final boolean bom;

    /** 原文件末尾是否有换行。很多工具链要求文件以换行结尾 */
    private final boolean trailingNewline;

    /** 文件内容，已统一成 \n 换行 */
    private final String text;

    private CodeFile(Path path, Charset charset, String eol, boolean bom,
                     boolean trailingNewline, String text) {
        this.path = path;
        this.charset = charset;
        this.eol = eol;
        this.bom = bom;
        this.trailingNewline = trailingNewline;
        this.text = text;
    }

    /**
     * 读取文件，同时记录下它的编码、换行、BOM 等「隐形属性」。
     *
     * @param path 文件路径
     * @return 内存映像，后续用 write() 写回时会自动还原这些属性
     */
    public static CodeFile read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("文件不存在: " + path);
        }
        byte[] raw = Files.readAllBytes(path);

        // 判断 BOM：EF BB BF 三个字节
        boolean bom = raw.length >= 3
                && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF;
        int offset = bom ? 3 : 0;

        Charset charset = detect(raw, offset);
        String s = new String(raw, offset, raw.length - offset, charset);

        // 记录换行符，再把内容统一成 \n 方便后续文本运算
        String eol = s.contains("\r\n") ? "\r\n" : "\n";
        boolean trailing = s.endsWith("\n") || s.endsWith("\r\n");
        s = s.replace("\r\n", "\n").replace("\r", "\n");

        return new CodeFile(path, charset, eol, bom, trailing, s);
    }

    /**
     * 探测编码：先按严格模式试 UTF-8，只要有一个字节序列非法就判定为 GBK。
     *
     * 这个做法比引入 juniversalchardet 之类的探测库简单得多，
     * 对「中文 Windows 项目基本只有 UTF-8 和 GBK 两种编码」这个现实足够用。
     */
    private static Charset detect(byte[] raw, int offset) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw, offset, raw.length - offset));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            return Charset.forName("GBK");
        }
    }

    /** 取文件内容（已统一成 \n 换行） */
    public String text() {
        return text;
    }

    /** 取原文件的换行符，用于日志展示 */
    public String eol() {
        return eol;
    }

    /**
     * 写回磁盘，自动还原编码 / 换行 / BOM / 末尾换行。
     *
     * 处理顺序不能变：先统一成 \n → 补末尾换行 → 再转成目标换行符 → 最后加 BOM。
     * 顺序错了就会产生 \r\r\n 这种脏换行。
     *
     * @param newText 新内容，允许含 \r\n，这里会统一处理
     */
    public void write(String newText) throws IOException {
        String t = newText.replace("\r\n", "\n");
        if (trailingNewline && !t.endsWith("\n")) {
            t = t + "\n";
        }
        // 原文件是 CRLF 就转回 CRLF，是 LF 就保持 LF
        String out = "\n".equals(eol) ? t : t.replace("\n", eol);
        byte[] bytes = out.getBytes(charset);

        // 原文件有 BOM 就补回去，没有就不加
        if (bom) {
            byte[] withBom = new byte[bytes.length + 3];
            withBom[0] = (byte) 0xEF;
            withBom[1] = (byte) 0xBB;
            withBom[2] = (byte) 0xBF;
            System.arraycopy(bytes, 0, withBom, 3, bytes.length);
            bytes = withBom;
        }
        Files.write(path, bytes);
    }

    /** 编码名，用于在工具返回结果里提示「这个文件是 GBK」 */
    public String charsetName() {
        return charset.name();
    }
}
