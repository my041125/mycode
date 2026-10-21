package mycode;

import java.util.Map;

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
}
