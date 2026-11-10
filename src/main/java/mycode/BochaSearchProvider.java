package mycode;

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
 * 博查（Bocha）搜索服务商实现。
 *
 * 为什么中文场景优先选它：
 *   - 国内可直连，不需要代理，响应稳定
 *   - 中文网页覆盖明显好于国外服务，查本地政策、活动、门票信息更准
 *   - 返回的 summary 字段已经是模型提炼过的摘要，省掉自己清洗网页的活
 * 申请地址：https://open.bochaai.com
 *
 * 报文要点：
 *   请求  POST /v1/web-search  {"query":..., "summary":true, "count":N}
 *   响应  data.webPages.value[] 里每个元素含 name / url / snippet / summary
 * @author my
 */
public class BochaSearchProvider implements SearchProvider {

    /** 接口地址，固定不变 */
    private static final String ENDPOINT = "https://api.bochaai.com/v1/web-search";

    /** JSON 序列化工具，线程安全，做成静态常量复用 */
    private static final ObjectMapper M = new ObjectMapper();

    /** 博查的 API Key，走 Authorization: Bearer 头 */
    private final String apiKey;

    /** 复用 HttpClient，避免每次搜索都新建连接池 */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    public BochaSearchProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    /** 服务商名称，会出现在工具返回文本里，方便排查到底是哪家在搜 */
    @Override
    public String name() {
        return "博查";
    }

    /**
     * 调用博查搜索接口。
     *
     * 两个细节：
     *   1. count 做了二次上限保护（最多 10 条），即使上层传了更大的值也不会失控
     *   2. summary=true 让服务端返回提炼后的摘要，比原始 snippet 更适合直接喂给模型
     */
    @Override
    public List<SearchResult> search(String query, int topK) throws Exception {
        // 组装请求体
        ObjectNode body = M.createObjectNode();
        body.put("query", query);
        body.put("summary", true);
        body.put("count", Math.min(topK, 10));

        // 发起 POST 请求
        HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            // 把响应体前 200 字带上，否则排查 401/429 时完全看不出原因
            throw new IllegalStateException("Bocha HTTP " + resp.statusCode() + ": "
                    + resp.body().substring(0, Math.min(200, resp.body().length())));
        }

        // 解析结果：summary 是模型提炼过的长摘要，snippet 是原始片段，优先用 summary
        List<SearchResult> out = new ArrayList<>();
        for (JsonNode r : M.readTree(resp.body()).path("data").path("webPages").path("value")) {
            String text = r.path("summary").asText("");
            if (text.isBlank()) text = r.path("snippet").asText("");
            out.add(SearchResult.of(r.path("name").asText(), r.path("url").asText(), text));
        }
        return out;
    }
}
