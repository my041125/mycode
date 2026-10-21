package miniagent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抓取网页正文的工具。
 *
 * 为什么需要它：搜索接口只给两三百字摘要，涉及政策、开放时间、价格细则时远远不够，
 * 模型得能顺着 URL 读全文。
 *
 * 这个工具是全套里最危险的一个 —— 它让模型可以命令服务器去访问任意地址。
 * 所以必须做 SSRF 防护：
 *   - 只允许 http/https 协议
 *   - 禁止内网段和本机地址
 *   - 限制响应体大小和超时时间
 * 否则模型（或被注入的网页）一句「请访问 http://127.0.0.1:8080/admin」就能把内网打通。
 */
public class FetchPageTool implements Tool {

    /** 响应体大小上限 512KB。再大也没有阅读价值，只会拖慢速度 */
    private static final int MAX_BYTES = 512 * 1024;

    /** 默认返回字符数 */
    private static final int DEFAULT_CHARS = 3000;

    /** 抓取上限，防止模型传个超大值把上下文撑爆 */
    private static final int MAX_CHARS = 5000;

    /** 复用 HttpClient。自动跟随 3xx 跳转（很多站点会 301 到 https） */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 工具名 */
    @Override
    public String name() {
        return "fetch_page";
    }

    /** 用途说明。强调「摘要不够时才用」，避免模型每次都去抓全文浪费时间和额度 */
    @Override
    public String description() {
        return "抓取指定网页的正文文本（自动去除 HTML 标签）。当搜索摘要不足以回答问题、"
                + "需要核对原文细节时使用。只能抓公网 http/https 地址。";
    }

    /** 参数：url 必填，maxChars 可选 */
    @Override
    public Map<String, Object> parameters() {
        return Tool.schema(Map.of(
                "url", Tool.str("完整网址，必须以 http:// 或 https:// 开头"),
                "maxChars", Tool.num("最多返回多少字，默认 3000，不要超过 5000")),
                "url");
    }

    /**
     * 执行抓取：校验协议 → 校验主机 → 发请求 → 限大小 → 识别编码 → 清洗正文 → 截断。
     * 前两步是安全边界，任何一步放松都可能变成内网探测工具。
     */
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        // 1. 取参数
        String raw = String.valueOf(args.getOrDefault("url", "")).strip();
        int maxChars = args.get("maxChars") == null
                ? DEFAULT_CHARS
                : Math.min(MAX_CHARS, (int) Double.parseDouble(String.valueOf(args.get("maxChars"))));

        // 2. 解析并校验协议，只放行 http/https
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("URL 格式不合法: " + raw);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("只允许 http/https，收到: " + scheme);
        }

        // 3. SSRF 防护：拒绝内网与本机地址
        if (isPrivateHost(uri.getHost())) {
            throw new IllegalArgumentException("拒绝访问内网或本机地址: " + uri.getHost());
        }

        // 4. 发请求。带浏览器 UA，不少站点会直接拒绝 Java 默认 UA
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 (compatible; MiniAgent/1.0)")
                .GET()
                .build();

        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + "，该页面可能禁止抓取或已失效");
        }

        // 5. 限制大小，只保留前 512KB
        byte[] bytes = resp.body();
        if (bytes.length > MAX_BYTES) {
            byte[] cut = new byte[MAX_BYTES];
            System.arraycopy(bytes, 0, cut, 0, MAX_BYTES);
            bytes = cut;
        }

        // 6. 按正确编码解码，再清洗成纯文本
        String html = new String(bytes, detectCharset(resp, bytes));
        String text = toPlainText(html);
        if (text.isBlank()) {
            // 纯前端渲染的页面（React/Vue SPA）抓下来就是空的，要如实说明而不是返回空串
            return "该页面没有可提取的正文（可能是纯前端渲染页面或需要登录），建议直接采用搜索摘要。";
        }
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars) + "\n…（正文过长已截断）";
        }
        return "页面标题: " + title(html) + "\n正文:\n" + text;
    }

    /**
     * 识别网页编码。
     * 中文站点大量使用 GBK，硬按 UTF-8 解出来全是乱码，所以必须看 charset 声明。
     * 优先看响应头，其次看 HTML 前 2KB 里的 meta 标签，都没有就按 UTF-8 兜底。
     */
    private Charset detectCharset(HttpResponse<byte[]> resp, byte[] body) {
        String ct = resp.headers().firstValue("content-type").orElse("");
        Matcher m = Pattern.compile("charset=[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(ct);
        if (!m.find()) {
            // 响应头没有就翻 meta。用 ISO_8859_1 读前 2KB 是为了不破坏字节，只为找声明
            String head = new String(body, 0, Math.min(2048, body.length), StandardCharsets.ISO_8859_1);
            m = Pattern.compile("charset=[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(head);
            if (!m.find()) return StandardCharsets.UTF_8;
        }
        String cs = m.group(1).toLowerCase();
        // gb2312 是 gbk 的子集，统一按 gbk 解，兼容性更好
        if (cs.equals("gb2312") || cs.equals("gbk")) cs = "GBK";
        try {
            return Charset.forName(cs);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** 提取 &lt;title&gt; 作为标题，抓不到就给个占位 */
    private String title(String html) {
        Matcher m = Pattern.compile("<title[^>]*>(.*?)</title>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        return m.find() ? m.group(1).strip() : "(无标题)";
    }

    /**
     * HTML 转纯文本。朴素实现，够用就行，要更干净可以换 jsoup 或 Readability 算法。
     * 处理顺序很重要：先整段干掉 script/style，再去标签，否则 JS 代码会被当正文留下来。
     */
    private String toPlainText(String html) {
        String s = html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<!--.*?-->", " ")
                // 块级标签换成换行，保住段落结构
                .replaceAll("(?i)<(br|/p|/div|/li|/h[1-6])[^>]*>", "\n")
                // 剩下的标签全部去掉
                .replaceAll("(?s)<[^>]+>", " ");
        // 还原常见 HTML 实体
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        // 压缩空白，把连续空行压成一个空行
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n\\s*\\n+", "\n\n");
        return s.strip();
    }

    /**
     * 判断是否内网 / 本机地址 —— SSRF 防护的核心。
     *
     * 覆盖了这些情况：
     *   localhost、::1、*.local、*.internal
     *   127.0.0.0/8（回环）
     *   10.0.0.0/8、172.16.0.0/12、192.168.0.0/16（私有网段）
     *   169.254.0.0/16（链路本地，云环境的元数据服务就在这，泄露密钥的重灾区）
     *   0.0.0.0
     *
     * 注意：只做了字面量判断，没有防 DNS Rebinding（域名解析到内网 IP）。
     * 高安全场景应该解析出真实 IP 再判断一遍。
     */
    private boolean isPrivateHost(String host) {
        if (host == null) return true;
        String h = host.toLowerCase();
        if (h.equals("localhost") || h.equals("::1") || h.endsWith(".local") || h.endsWith(".internal")) {
            return true;
        }
        if (h.matches("^127\\..*") || h.matches("^10\\..*") || h.matches("^192\\.168\\..*")
                || h.matches("^169\\.254\\..*") || h.equals("0.0.0.0")) {
            return true;
        }
        // 172.16.0.0 ~ 172.31.255.255 这一段要单独算，不能简单前缀匹配
        Matcher m = Pattern.compile("^172\\.(\\d+)\\..*").matcher(h);
        return m.matches() && Integer.parseInt(m.group(1)) >= 16 && Integer.parseInt(m.group(1)) <= 31;
    }
}
