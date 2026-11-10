package mycode;

import java.util.Map;

/**
 * 用函数式方式定义工具，避免每个小工具都单独写一个类。
 *
 * 用法：
 *   new SimpleTool(
 *       "get_weather",                    // 名字
 *       "查询城市当日天气",                  // 给模型看的说明
 *       Tool.schema(Map.of("city", Tool.str("城市名")), "city"),   // 参数结构
 *       args -> "多云 22~28℃")              // 真正的执行逻辑
 *
 * 参数说明：
 *   name / description / parameters 直接对应 Tool 接口的三个方法
 *   handler 是一个函数式接口，抛出的异常由 ToolRegistry 统一兜住
 * @author my
 */
public record SimpleTool(String name, String description, Map<String, Object> parameters, Handler handler)
        implements Tool {

    /** 工具的实际执行逻辑，允许抛异常（Registry 会转成 ERROR 文本回填给模型） */
    @FunctionalInterface
    public interface Handler {
        String run(Map<String, Object> args) throws Exception;
    }

    /** 转调 handler，把 record 适配成 Tool 接口 */
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        return handler.run(args);
    }
}
