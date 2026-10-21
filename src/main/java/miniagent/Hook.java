package miniagent;

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
}
