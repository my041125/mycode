package miniagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内核部件 1 的容器：工具注册表 + 安全分发。
 *
 * 这里最容易被新手写错的一点：dispatch 绝对不能把异常抛出去。
 * 工具报错必须变成一条 observation 喂回给模型，让模型自己决定换参数重试还是放弃。
 * 一旦异常炸穿循环，agent 就退化成了「一次性 API 调用」，ReAct 的意义就没了。
 */
public class ToolRegistry {

    /** 工具名 -> 工具实例。用 LinkedHashMap 保证遍历顺序与注册顺序一致，便于排查 */
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /**
     * 注册工具，支持链式调用。
     * 重名直接抛异常：工具名是模型调用的唯一凭据，重名会导致调用打到一个意料之外的实现上。
     */
    public ToolRegistry register(Tool... ts) {
        for (Tool t : ts) {
            if (tools.put(t.name(), t) != null) {
                throw new IllegalArgumentException("工具重名: " + t.name());
            }
        }
        return this;
    }

    /** 返回所有工具，每轮都要传给模型用于声明可用能力 */
    public List<Tool> all() {
        return new ArrayList<>(tools.values());
    }

    /** 判断某个工具是否已注册，钩子做白名单校验时会用到 */
    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /**
     * 安全分发：永不抛异常，永远返回一段给模型看的文本。
     *
     * 三类失败都转成 ERROR 文本，让模型自己纠错：
     *   1. 工具名不存在 —— 模型幻觉出了没有的工具
     *   2. 参数不合法   —— 模型少传字段或类型不对
     *   3. 执行出错     —— 网络、数据库、业务异常
     */
    public String dispatch(String name, Map<String, Object> args) {
        Tool tool = tools.get(name);
        if (tool == null) {
            // 把可用工具列出来，模型下一轮通常就能自己改对
            return "ERROR: 不存在名为 " + name + " 的工具。可用工具: " + tools.keySet();
        }
        try {
            String result = tool.execute(args == null ? Map.of() : args);
            return result == null ? "OK (无返回内容)" : result;
        } catch (IllegalArgumentException e) {
            // 参数问题单独区分：这类错误模型改一下参数就能成功，不必放弃
            return "ERROR: 参数不合法 - " + e.getMessage();
        } catch (Exception e) {
            return "ERROR: 工具执行失败 - " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * 生成工具清单，追加到 system prompt 末尾，让模型知道它有什么能力。
     *
     * 为什么要塞进 system prompt 而不是每次单独发一条消息：
     * 清单是固定背景信息，放 system 里不会每轮产生新的对话消息，更省 token。
     */
    public String describe() {
        if (tools.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n你可以调用以下工具：\n");
        for (Tool t : tools.values()) {
            sb.append("- ").append(t.name()).append(": ").append(t.description()).append('\n');
        }
        sb.append("当需要外部信息时必须调用工具，不要凭空编造；拿到结果后再作答。");
        return sb.toString();
    }
}
