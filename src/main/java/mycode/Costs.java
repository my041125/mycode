package mycode;

/**
 * 成本估算。
 *
 * 为什么不内置各家价格表：模型价格变动非常频繁，写死在代码里迟早变成错误信息。
 * 这里改成从 agent.properties 读可选配置：
 *
 *   priceInputPerK=0.002       每千输入 token 的价格（元，未命中缓存）
 *   priceCacheHitPerK=0.0002   每千「命中缓存」输入 token 的价格（元）
 *   priceOutputPerK=0.003      每千输出 token 的价格（元）
 *
 * DeepSeek 的前缀缓存自动生效：历史上下文重复发送时命中部分按约 1/10 计费，
 * 所以输入实际按两档算 —— 命中部分用 priceCacheHitPerK，未命中部分用 priceInputPerK。
 * 不配价格就只显示 token 数，不显示金额 —— 宁可没有，也不给一个错的数字。
 * @author my
 */
public final class Costs {

    /** 每千输入 token 的价格（未命中缓存），单位元；未配置时为 0 */
    private static double priceInput() {
        return parse(AgentConfig.get("priceInputPerK"));
    }

    /** 每千「命中缓存」输入 token 的价格，单位元；未配置时为 0 */
    private static double priceCacheHit() {
        return parse(AgentConfig.get("priceCacheHitPerK"));
    }

    /** 每千输出 token 的价格，单位元；未配置时为 0 */
    private static double priceOutput() {
        return parse(AgentConfig.get("priceOutputPerK"));
    }

    /**
     * 是否配置了价格。没配置时界面只显示 token。
     */
    public static boolean configured() {
        return priceInput() > 0 || priceOutput() > 0;
    }

    /** 输入里命中缓存的部分。个别服务商会报出比 prompt 还大的数，夹一下防负数 */
    private static int cachedOf(Usage usage) {
        return Math.min(Math.max(usage.cacheReadTokens(), 0), usage.promptTokens());
    }

    /**
     * 估算费用。
     * 命中部分若没单独配价格，就按普通输入价算 —— 宁可把费用报高，也不报偏低的假优惠。
     *
     * @return 形如 "0.0123 元"；未配置价格时返回 null
     */
    public static String money(Usage usage) {
        if (usage == null || !configured()) return null;
        int cached = cachedOf(usage);
        double hitPrice = priceCacheHit() > 0 ? priceCacheHit() : priceInput();
        double yuan = (usage.promptTokens() - cached) / 1000.0 * priceInput()
                + cached / 1000.0 * hitPrice
                + usage.completionTokens() / 1000.0 * priceOutput();
        return String.format("%.4f 元", yuan);
    }

    /**
     * 生成一段可读的用量摘要，例如：
     *   1234 输入 + 567 输出 = 1801 tokens · 缓存命中 900/1234（73%），省 0.0016 元（约 0.0123 元）
     * 缓存命中率只在有过命中时才显示；省钱额需要配置价格，否则只报比例不报钱。
     */
    public static String describe(Usage usage) {
        if (usage == null) return "无用量数据";
        String money = money(usage);
        return usage.toString() + cacheLine(usage) + (money == null ? "" : "（约 " + money + "）");
    }

    /** 缓存命中摘要；服务商不报告或一次都没命中过时返回空串 */
    private static String cacheLine(Usage usage) {
        int hit = cachedOf(usage);
        if (hit <= 0) return "";
        int prompt = usage.promptTokens();
        long pct = prompt <= 0 ? 0 : Math.round(hit * 100.0 / prompt);
        String saved = savedMoney(hit);
        return " · 缓存命中 " + hit + "/" + prompt + "（" + pct + "%）"
                + (saved == null ? "" : "，省 " + saved);
    }

    /** 这部分命中 token 相比全价省了多少钱；没配全两档价格时返回 null（不瞎猜） */
    private static String savedMoney(int hitTokens) {
        if (priceInput() <= 0 || priceCacheHit() <= 0) return null;
        double saved = hitTokens / 1000.0 * (priceInput() - priceCacheHit());
        return String.format("%.4f 元", Math.max(0, saved));
    }

    private static double parse(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return Math.max(0, Double.parseDouble(s.strip()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Costs() {
    }
}
