package mycode;

import java.util.List;

/**
 * 离线假搜索：不联网也能验证「搜索 → 引用」整条链路。
 *
 * 两个作用：
 *   1. 开发调试时省额度 —— 不用真调接口就能把循环跑通
 *   2. 做回归测试 —— 返回固定数据集，输出可预期，方便断言
 *
 * 真实项目里应该把它做成「录制回放」：把线上真实响应录下来，
 * 每次改完代码跑一遍，确认 agent 的最终回答没变差。
 * @author my
 */
public class MockSearchProvider implements SearchProvider {

    /** 服务商名称，会出现在工具返回文本里，一眼看出是离线数据 */
    @Override
    public String name() {
        return "离线模拟";
    }

    /**
     * 返回写死的三条零陵区旅游数据。
     *
     * 关键词不匹配时返回空列表而不是 null —— 这样上层工具能统一走
     * 「没搜到，建议换关键词」的分支，不用单独判空。
     */
    @Override
    public List<SearchResult> search(String query, int topK) {
        if (query.contains("柳子庙") || query.contains("零陵")) {
            return List.of(
                    SearchResult.of("柳子庙 - 零陵区人民政府官网",
                            "https://www.lingling.gov.cn/spot/liuzimiao",
                            "柳子庙位于永州市零陵区潇水西岸，为纪念唐代文学家柳宗元而建。开放时间 08:00-17:30，"
                                    + "成人票 60 元，学生凭学生证半价。"),
                    SearchResult.of("零陵古城游玩攻略（2026 最新）",
                            "https://travel.example.com/lingling-guide",
                            "零陵古城免大门票，内部小景点单独收费。夜间灯光秀每周五、六 19:30 开始，"
                                    + "持续约 40 分钟。建议傍晚入园，白天逛柳子庙、晚上看灯光秀。"),
                    SearchResult.of("周家大院修缮后重新开放",
                            "https://news.example.com/zhoujiadayuan",
                            "零陵区周家大院完成修缮，2026 年 3 月起恢复开放，门票 35 元，每日限流 800 人。"))
                    // 按 topK 截断，模拟真实服务商的行为，顺便验证上层传参是否生效
                    .subList(0, Math.min(topK, 3));
        }
        return List.of();
    }
}
