package mycode;

import java.util.Map;

/**
 * 模型要求调用某个工具。
 *
 * 字段说明：
 *   id       —— 本次调用的唯一标识，回填结果时靠它配对（Anthropic 叫 tool_use_id）
 *   name     —— 工具名
 *   argsJson —— 模型返回的原始参数字符串，必须原样保留
 *   args     —— 解析后的参数，方便工具直接取用
 *
 * 为什么要同时保留 argsJson 和 args：
 * OpenAI 协议要求把 assistant 的 tool_calls 原样发回给下一轮，
 * 如果只存解析后的 Map 再序列化，字段顺序和数字格式可能与原文不同，
 * 部分严格要求一致性的服务商会因此报错。args 只是给本地代码用的便利视图。
 *
 * 注：原先放在本文件里的 ChatResult 已独立为 ChatResult.java，并升级为 public。
 * @author my
 */
public record ToolCall(String id, String name, String argsJson, Map<String, Object> args) {
}
