package mycode;

/**
 * 成本估算。
 *
 * 为什么不内置各家价格表：模型价格变动非常频繁，写死在代码里迟早变成错误信息。
 * 这里改成从 agent.properties 读两个可选配置：
 *
 *   priceInputPerK=0.004    每千输入 token 的价格（元）
 *   priceOutputPerK=0.012   每千输出 token 的价格（元）
 *
 * 不配就只显示 token 数，不显示金额 —— 宁可没有，也不给一个错的数字。
 */
public final class Costs {

    /** 每千输入 token 的价格，单位元；未配置时为 0 */
    private static double priceInput() {
        return parse(AgentConfig.get("priceInputPerK"));
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

    /**
     * 估算费用。
     *
     * @return 形如 "0.0123 元"；未配置价格时返回 null
     */
    public static String money(Usage usage) {
        if (usage == null || !configured()) return null;
        double yuan = usage.promptTokens() / 1000.0 * priceInput()
                + usage.completionTokens() / 1000.0 * priceOutput();
        return String.format("%.4f 元", yuan);
    }

    /**
     * 生成一段可读的用量摘要，例如：
     *   1234 输入 + 567 输出 = 1801 tokens（约 0.0123 元）
     */
    public static String describe(Usage usage) {
        if (usage == null) return "无用量数据";
        String money = money(usage);
        return usage.toString() + (money == null ? "" : "（约 " + money + "）");
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
