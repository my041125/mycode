package mycode;

import java.util.Map;

/**
 * 内核部件 4：拦截钩子。
 *
 * 这是把「玩具」和「能上生产」区分开的东西：日志、限流、审计、人工审批、
 * 危险工具拦截，全部挂在这里，不需要改主循环一行代码。
 * Spring AI 2.0 把这个思路叫 Advisor 链，本质就是拦截器。
 *
 * 四个切入点正好夹在循环的两个关键位置上：
 *   调用模型前 → 调模型后 → 执行工具前 → 执行工具后
 * @author my
 */
public interface Hook {

    /** 调用模型之前。适合做：日志、上下文裁剪、注入临时提示、计费埋点 */
    default void beforeLlm(AgentContext ctx) {
    }

    /** 调用模型之后。适合做：记录 token 消耗、统计工具调用意图、链路追踪 */
    default void afterLlm(ChatResult result) {
    }

    /**
     * 执行工具之前。返回 false 则拒绝执行该工具。
     *
     * 拒绝之后模型不会直接崩掉，而是收到一条「被拦截」的 observation，
     * 它能自己决定换个方式或者告诉用户不具备权限 —— 这是人工审批卡点的实现方式。
     */
    default boolean beforeTool(String name, Map<String, Object> args) {
        return true;
    }

    /** 执行工具之后。适合做：审计日志、结果脱敏、耗时统计 */
    default void afterTool(String name, String result) {
    }

    /**
     * 上下文被压缩之后。
     *
     * 这是一次「记忆改写」，用户有权知道发生了什么，否则会奇怪
     * 「它怎么突然忘了我刚才说的」。
     *
     * @param removedMessages 移除了多少条消息
     * @param savedChars      净省下多少字
     * @param summary         新的历史摘要
     */
    default void onContextCompressed(int removedMessages, int savedChars, String summary) {
    }

    /**
     * 模型调用瞬时失败（限流、超时、断连），即将按退避等待后重试。
     *
     * 不提示用户的话，等待的几秒看起来就像卡死了。
     *
     * @param attempt    即将进行的是第几次尝试（从 1 计，首次调用不算）
     * @param waitMillis 重试前要等多少毫秒
     * @param reason     失败原因（原始异常消息）
     */
    default void onModelRetry(int attempt, long waitMillis, String reason) {
    }
}
