package mycode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SKILL.md 的 frontmatter 解析器。
 *
 * 为什么自己写而不引 SnakeYAML：SKILL.md 的 frontmatter 实际上只用到了
 * 「key: value」这一种形式，引一个 YAML 库（几百 KB）换 20 行代码不值当。
 *
 * 支持的写法：
 *   name: docx
 *   description: "带引号的描述"
 *   allowed-tools: Read, Write, Edit
 * 不支持的（按「整块跳过」处理，不影响其它字段）：
 *   多行折叠字符串（> 或 |）、嵌套对象、数组用 - 逐行写
 */
final class Frontmatter {

    /** 解析出的键值对，键统一小写 */
    private final Map<String, String> values;

    /** frontmatter 之后的正文 */
    private final String body;

    private Frontmatter(Map<String, String> values, String body) {
        this.values = values;
        this.body = body;
    }

    /**
     * 切分 frontmatter 与正文。
     * 没有 frontmatter 时，整篇都算正文（这样普通 markdown 也能当技能用）。
     */
    static Frontmatter split(String text) {
        Map<String, String> map = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) return new Frontmatter(map, "");

        String normalized = text.replace("\r\n", "\n");
        // 必须以 --- 开头，且后面有另一条 --- 作为结束
        if (!normalized.startsWith("---")) return new Frontmatter(map, normalized);

        int headerEnd = normalized.indexOf('\n');
        if (headerEnd < 0) return new Frontmatter(map, normalized);

        int close = normalized.indexOf("\n---", headerEnd);
        if (close < 0) return new Frontmatter(map, normalized);

        String yaml = normalized.substring(headerEnd + 1, close);
        int bodyStart = normalized.indexOf('\n', close + 1);
        String body = bodyStart < 0 ? "" : normalized.substring(bodyStart + 1);

        for (String raw : yaml.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            // 嵌套行（以空格或 - 开头）不支持，直接跳过而不是把父键覆盖成垃圾值
            if (raw.startsWith(" ") || raw.startsWith("\t") || line.startsWith("- ")) continue;
            int i = line.indexOf(':');
            if (i <= 0) continue;
            String key = line.substring(0, i).strip().toLowerCase();
            String value = unquote(line.substring(i + 1).strip());
            // 同一个键只取第一次出现
            map.putIfAbsent(key, value);
        }
        return new Frontmatter(map, body);
    }

    /** 取某个字段，不存在返回 null */
    String value(String key) {
        return values.get(key.toLowerCase());
    }

    /** 正文 */
    String body() {
        return body;
    }

    /** 去掉成对的引号 */
    private static String unquote(String v) {
        if (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }
}
