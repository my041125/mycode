package mycode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 内核部件 1：工具。
 *
 * 一个工具由四样东西组成：
 *   1. name()        —— 模型调用时用的名字，全局唯一
 *   2. description() —— 给模型看的用途说明，这段文字直接决定调用准确率
 *   3. parameters()  —— 参数结构（JSON Schema），告诉模型该传哪些字段
 *   4. execute()     —— 真正干活的代码
 *
 * 设计上刻意让 parameters() 返回普通 Map，不暴露任何 JSON 库的类型，
 * 这样内核层就不绑定 Jackson（换 Fastjson / Gson 也不用改这一层）。
 * @author my
 */
public interface Tool {

    /** 工具名。建议小写下划线，例：web_search、calc_ticket_price */
    String name();

    /**
     * 用途说明。写法要点：
     *   - 说清楚「什么时候该用」，也要说「什么时候不该用」
     *   - 别写「查询数据」这种空话，模型只能靠这段文字做决策
     */
    String description();

    /** 参数结构，标准 JSON Schema。模型据此生成调用参数，缺字段会直接报错 */
    Map<String, Object> parameters();

    /**
     * 执行工具。
     *
     * 允许抛异常：ToolRegistry.dispatch 会统一捕获并转成 observation 文本，
     * 让模型自己判断是改参数重试还是放弃。所以这里不用写 try-catch 到处兜底。
     *
     * @param args 模型给的参数，可能缺字段或类型不对，实现方要自己校验
     * @return 结果文本。这段文本会原样进入模型上下文，注意别太长
     */
    String execute(Map<String, Object> args) throws Exception;

    /**
     * 本工具单条结果允许的最大字符数，0 表示用 Agent 的全局默认。
     *
     * 绝大多数工具不需要覆盖。需要覆盖的典型是 task：它返回的是子代理
     * 精炼后的最终交付物（而不是一条原始命令输出），按全局默认截断会把
     * 结论砍成半截话，主代理拿到后没法续接任务。
     */
    default int resultCharLimit() {
        return 0;
    }

    // ---------------- 便捷构造：省去手写 schema 的样板代码 ----------------

    /**
     * 构造对象类型的 schema。
     *
     * @param props    属性名 -> 属性定义（一般用下面的 str() / num() 生成）
     * @param required 必填字段名，可省略
     */
    static Map<String, Object> schema(Map<String, Object> props, String... required) {
        return Map.of("type", "object", "properties", props, "required", required);
    }

    /** 定义一个字符串参数 */
    static Map<String, Object> str(String desc) {
        return Map.of("type", "string", "description", desc);
    }

    /** 定义一个数值参数（整数和小数都用它，模型对 number 的支持最好） */
    static Map<String, Object> num(String desc) {
        return Map.of("type", "number", "description", desc);
    }

    /**
     * 递归把 schema 里的所有 Map 换成按 key 排序的 TreeMap，List 逐项递归。
     *
     * 为什么需要它：工具声明位于请求报文的最前端，而 DeepSeek 的前缀缓存按
     * 字节精确匹配。schema 大多用 Map.of 构建，其遍历顺序每次 JVM 启动都随机，
     * 进程一重启，同一份工具声明序列化出来字段顺序就变了，缓存从第一个字节起
     * 全部未命中。统一排序后，无论工具怎么声明 schema，序列化字节都恒定，
     * 重启后「工具声明 + system」这段公共前缀仍能命中缓存。
     * 排序只是换了字段出现顺序，JSON Schema 语义不受影响。
     */
    static Object canonical(Object node) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> out = new TreeMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), canonical(e.getValue()));
            }
            return out;
        }
        if (node instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(canonical(o));
            return out;
        }
        return node;
    }

    /**
     * 把模型给的参数值容错转成 int。
     * 模型可能传数字、字符串数字或小数，解析不了就用默认值 ——
     * 不能让一次类型失误废掉整个工具调用。
     */
    static int toInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try {
            return (int) Double.parseDouble(String.valueOf(v).strip());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
