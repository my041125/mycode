package miniagent;

import java.util.List;

/**
 * 搜索能力的抽象。内核只认这个接口，换服务商改一行装配代码即可。
 *
 * 注意 search 签名里的 topK：结果条数必须由调用方（WebSearchTool）决定，
 * 绝不能让模型自己传 —— 模型要 50 条的时候，上下文基本就废了。
 */
public interface SearchProvider {

    /** 服务商名称，会出现在工具返回结果里，方便排查到底是谁在搜 */
    String name();

    /**
     * 执行搜索。
     *
     * @param query 关键词，自然语言短句效果最好
     * @param topK  最多返回几条，实现方应再做一次上限保护
     * @return 结果列表，没搜到返回空列表（不要返回 null，也别抛异常）
     */
    List<SearchResult> search(String query, int topK) throws Exception;
}
