package miniagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Tavily 搜索服务商实现。
 *
 * 特点：专为 LLM 设计，返回的 content 字段已经是提炼过的片段，
 * 不需要自己抓网页再清洗，接入成本最低。
 * 代价：国内访问偶尔不稳，通常要配代理；中文网页覆盖不如博查。
 *
 * 报文要点：
 *   请求  POST /search  {"query":..., "max_results":N, "search_depth":"basic"}
 *   鉴权  Authorization: Bearer tvly-xxx
 *   响应  results[] 里每个元素含 title / url / content
 */
public class TavilySearchProvider implements SearchProvider {

    /** 接口地址，固定不变 */
    private static final String ENDPOINT = "https://api.tavily.com/search";

    /** JSON 序列化工具，线程安全，做成静态常量复用 */
    private static final ObjectMapper M = new ObjectMapper();

    /** Tavily 的 API Key，走 Authorization: Bearer 头 */
    private final String apiKey;

    /** 复用 HttpClient，避免每次搜索都新建连接池 */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    public TavilySearchProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    /** 服务商名称，会出现在工具返回文本里 */
    @Override
    public String name() {
        return "Tavily";
    }

    /**
     * 调用 Tavily 搜索接口。
     *
     * 两个参数选择的理由：
     *   search_depth=basic  —— advanced 会明显更慢且更贵，agent 循环里等不起
     *   include_answer=false —— 不需要它自带的问答摘要，我们要的是原始来源链接
     */
    @Override
    public List<SearchResult> search(String query, int topK) throws Exception {
        // 组装请求体
        ObjectNode body = M.createObjectNode();
        body.put("query", query);
        body.put("max_results", Math.min(topK, 10));
        body.put("search_depth", "basic");
        body.put("include_answer", false);

        // 发起 POST 请求
        HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Tavily HTTP " + resp.statusCode() + ": "
                    + resp.body().substring(0, Math.min(200, resp.body().length())));
        }

        // 解析结果：Tavily 的正文在 content 字段里
        List<SearchResult> out = new ArrayList<>();
        for (JsonNode r : M.readTree(resp.body()).path("results")) {
            out.add(SearchResult.of(r.path("title").asText(), r.path("url").asText(),
                    r.path("content").asText()));
        }
        return out;
    }
}
