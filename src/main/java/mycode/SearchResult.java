package mycode;

/**
 * 一条搜索结果。
 *
 * 三个字段都不可省：
 *   title   —— 让模型知道这条讲什么
 *   url     —— 用户要能核实来源，回答里必须能给出链接
 *   snippet —— 摘要，喂给模型的正文，太长会撑爆上下文，由使用方截断
 * @author my
 */
public record SearchResult(String title, String url, String snippet) {

    /**
     * 宽松构造：各家服务商的字段名和小毛病都不一样
     * （标题可能是 null、摘要里可能有一堆换行），统一在这里收敛掉，
     * 上层就不用为每个服务商单独写清洗逻辑。
     */
    public static SearchResult of(String title, String url, String snippet) {
        return new SearchResult(
                title == null ? "(无标题)" : title.strip(),
                url == null ? "" : url.strip(),
                // 把连续空白压成单个空格，避免摘要里一堆换行把上下文切碎
                snippet == null ? "" : snippet.strip().replaceAll("\\s+", " "));
    }
}
