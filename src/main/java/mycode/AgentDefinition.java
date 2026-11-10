package mycode;

import java.util.List;

/**
 * 一个子代理（subagent）的定义。
 *
 * 沿用 Claude Code 的子代理格式：一个 markdown 文件，frontmatter 声明元信息，
 * 正文就是子代理的系统提示词：
 *
 *   ---
 *   name: explorer
 *   description: 什么时候派给它（决定主代理能否选对人）
 *   tools: list_files, read_code, run_command
 *   ---
 *   正文：子代理自己的行为准则，派发时作为它的 system prompt
 *
 * 与技能（Skill）的区别：技能是「一段按需加载的操作说明」，交给当前代理自己执行；
 * 子代理是「一个独立干活的分身」，有自己的上下文和工具表，干完只交回最终结论。
 *
 * 设计上只保留真正需要的字段，其余（model / effort）解析后忽略 ——
 * 当前没有多模型路由，子代理与主代理共用同一个模型客户端。
 *
 * @param name         子代理名，task 工具的 agent 参数用它来选人
 * @param description  描述，启动时注入 system 供主代理判断该派谁（会被截断）
 * @param systemPrompt 子代理的系统提示词（定义文件的正文）
 * @param allowedTools 工具白名单；为空表示不限制，继承主代理的全部工具
 * @param source       来源标签（项目 / 个人 / 内置），让用户知道定义从哪来
 * @author my
 */
public record AgentDefinition(String name, String description, String systemPrompt,
                              List<String> allowedTools, String source) {

    /** 是否声明了工具白名单。没声明就继承主代理的工具表 */
    public boolean restrictsTools() {
        return !allowedTools.isEmpty();
    }
}
