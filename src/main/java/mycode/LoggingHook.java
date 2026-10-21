package mycode;

import java.util.Map;

/**
 * 日志钩子：把主循环的每一步都打印出来。
 *
 * 调试 agent 时这是最值钱的一件东西 —— 出问题时你不需要猜，
 * 直接能看到：上下文多长了、模型想调哪个工具、参数是什么、工具返回了什么、哪一轮收敛。
 *
 * 生产环境不要用它（每次都打控制台），应该换成写日志文件或上报链路追踪。
 */
public class LoggingHook implements Hook {

    /** 当前轮次计数，每调一次模型加一，用来让日志能对上「第几轮」 */
    private int step = 0;

    /** 调用模型前：打印轮次和上下文长度。上下文突然暴涨通常是工具结果没截断 */
    @Override
    public void beforeLlm(AgentContext ctx) {
        step++;
        System.out.printf("%n  [第 %d 轮] 上下文约 %d 字，发起模型调用%n", step, ctx.estimateTokens());
    }

    /** 调用模型后：把模型的意图翻译成人话，是「要调工具」还是「可以回答了」 */
    @Override
    public void afterLlm(ChatResult result) {
        if (result.hasToolCalls()) {
            result.toolCalls().forEach(c ->
                    System.out.printf("    → 模型要求调用 %s(%s)%n", c.name(), c.argsJson()));
        } else {
            System.out.println("    → 模型给出最终文本，不再调用工具");
        }
    }

    /**
     * 执行工具前：演示审批 / 风控钩子。
     * 真实系统在这里接人工确认、频率限制或危险工具黑名单，
     * 例如「支付、删除、发消息」这类不可逆操作必须先过审批。
     */
    @Override
    public boolean beforeTool(String name, Map<String, Object> args) {
        boolean dangerous = name.startsWith("delete_") || name.startsWith("pay_");
        if (dangerous) System.out.println("    ✗ 拦截高风险工具: " + name);
        return !dangerous;
    }

    /** 执行工具后：打印观察结果。换行压成 ⏎ 并截断，避免刷屏 */
    @Override
    public void afterTool(String name, String result) {
        String one = result.replace("\n", " ⏎ ");
        if (one.length() > 90) one = one.substring(0, 90) + "...";
        System.out.println("    ← 观察: " + one);
    }
}
