package miniagent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「搜索服务」包装成内核认识的 Tool。
 *
 * 三件必须做的事，缺一件线上就会出问题：
 *   1. 条数写死上限（topK），不能让模型一次要 50 条把上下文冲爆；
 *   2. 结果按固定模板格式化，并明确要求模型用 [编号] 引用，
 *      否则它会把搜索结果当成自己本来就懂的知识，用户没法核实；
 *   3. 加 TTL 缓存，同一个 query 短时间内重复搜索既烧钱又慢。
 *
 * 关键认知：搜索是「工具」，不是「模型能力」。
 * 做成工具，条数、超时、缓存、来源全部可控；用模型内置联网，这些都管不了。
 */
public class WebSearchTool implements Tool {

    /** 缓存有效期，单位秒。10 分钟内的相同 query 直接走缓存 */
    private static final int TTL_SECONDS = 600;

    /** 缓存最大条数，超出按 LRU 淘汰最久未使用的 */
    private static final int MAX_CACHE = 64;

    /** 单条摘要的字符上限。三条各 500 字就是 1500 字，多搜几次上下文就满了 */
    private static final int SNIPPET_LIMIT = 220;

    /** 真正的搜索实现，可能是博查、Tavily 或离线模拟 */
    private final SearchProvider provider;

    /** 每次搜索返回几条。这个是代码里定死的，不暴露给模型 */
    private final int topK;

    /**
     * 现成的 LRU 缓存：LinkedHashMap 的 accessOrder=true 会按访问顺序排序，
     * 重写 removeEldestEntry 就能在超出容量时自动淘汰最老的一条。
     */
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > MAX_CACHE;
        }
    };

    /**
     * 一条缓存记录。
     *
     * @param text     已经格式化好的结果文本，命中时直接返回，不用重新拼
     * @param expireAt 过期时间戳（毫秒）
     */
    private record CacheEntry(String text, long expireAt) {
        /** 是否还在有效期内 */
        boolean alive() {
            return System.currentTimeMillis() < expireAt;
        }
    }

    /**
     * @param provider 搜索服务商
     * @param topK     每次返回几条，建议 3~5
     */
    public WebSearchTool(SearchProvider provider, int topK) {
        this.provider = provider;
        this.topK = topK;
    }

    /** 工具名，模型靠它来调用 */
    @Override
    public String name() {
        return "web_search";
    }

    /**
     * 用途说明。写得好的关键：既要说「什么时候用」，也要说「什么时候别用」。
     * 这里明确拦住了「查系统内部数据」的场景，否则模型会用搜索去查数据库里本来就有的东西。
     */
    @Override
    public String description() {
        return "联网搜索公开网页。用于查询实时信息：新闻、活动、开放时间、门票价格变动、交通等。"
                + "景点、商户、预约这类系统内部数据不要用它，应该用系统内的查询工具。";
    }

    /** 参数只有一个 query，且必填 */
    @Override
    public Map<String, Object> parameters() {
        return Tool.schema(Map.of(
                "query", Tool.str("搜索关键词，用自然语言短句，例如「零陵古城 夜间灯光秀 时间」")),
                "query");
    }

    /**
     * 执行搜索：校验 → 查缓存 → 调服务商 → 格式化 → 回写缓存。
     *
     * 格式化模板是刻意设计的，包含三段信息：
     *   [编号] 标题   —— 让模型能引用
     *   URL          —— 让用户能核实
     *   摘要         —— 让模型有内容可用
     * 末尾那句「引用要求」不是装饰：不写这句，模型十有八九会直接把搜索内容当自己的话讲。
     */
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        // 1. 参数校验：模型偶尔会漏传或传空串
        Object q = args.get("query");
        if (q == null || String.valueOf(q).isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        String query = String.valueOf(q).strip();

        // 2. 查缓存
        CacheEntry hit = cache.get(query);
        if (hit != null && hit.alive()) {
            return hit.text() + "\n（来自缓存，未再次请求搜索服务）";
        }

        // 3. 调服务商
        List<SearchResult> results = provider.search(query, topK);
        if (results == null || results.isEmpty()) {
            // 没搜到也要给出可执行的建议，别只回一句「无结果」
            return "检索「" + query + "」没有返回结果。建议：换更短的关键词、去掉限定词，或直接告知用户未查到。";
        }

        // 4. 按固定模板格式化
        StringBuilder sb = new StringBuilder();
        sb.append("检索「").append(query).append("」共 ").append(results.size())
                .append(" 条（来源: ").append(provider.name()).append("）：\n");
        int i = 1;
        for (SearchResult r : results) {
            sb.append('[').append(i++).append("] ").append(r.title()).append('\n');
            sb.append("    URL: ").append(r.url()).append('\n');
            sb.append("    摘要: ").append(snippet(r.snippet())).append('\n');
        }
        sb.append("引用要求：回答时用 [1][2] 标注来源，并附上 URL；摘要没有覆盖的细节不要臆测。");

        // 5. 回写缓存再返回
        String text = sb.toString();
        cache.put(query, new CacheEntry(text, System.currentTimeMillis() + TTL_SECONDS * 1000L));
        return text;
    }

    /** 截断单条摘要，避免某条结果特别长把其它结果挤掉 */
    private String snippet(String s) {
        if (s == null) return "";
        return s.length() <= SNIPPET_LIMIT ? s : s.substring(0, SNIPPET_LIMIT) + "…";
    }
}
