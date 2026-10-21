package mycode;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 协议对照工具：把同一段对话分别按 OpenAI 和 Anthropic 两种格式渲染出来。
 *
 * 这是本项目里最省钱的调试手段 —— 不用调真 API 就能确认协议转换写对了没有。
 * 特别要看两点：
 *   1. system 提示词去哪了（OpenAI 在 messages 里，Anthropic 在顶层字段）
 *   2. 两条并行工具结果有没有被合并成一条 user 消息（Anthropic 的硬性要求）
 *
 *   mvn -q exec:java -Dexec.args="--dump"
 */
public class RequestDump {

    /** 用于格式化输出 JSON，方便人眼对比 */
    private static final ObjectMapper M = new ObjectMapper();

    /**
     * 打印两种协议的请求报文。
     * 构造的对话刻意包含「并行工具调用」，这是两种协议差异最大的地方。
     */
    public static void run() throws Exception {
        // 1. 手工拼一段对话：system → user → assistant(两个工具调用) → 两条工具结果
        AgentContext ctx = AgentContext.of("你是乡村文旅助手。");
        ctx.user("零陵区有什么景点？两个人要多少钱？");
        ctx.assistant("我先查一下景点和价格。", List.of(
                new ToolCall("call_1", "search_spots", "{\"district\":\"零陵区\"}", Map.of("district", "零陵区")),
                new ToolCall("call_2", "get_weather", "{\"city\":\"永州\"}", Map.of("city", "永州"))));
        ctx.toolResult("call_1", "search_spots", "柳子庙 60 元；零陵古城 40 元");
        ctx.toolResult("call_2", "get_weather", "多云 22~28℃");

        // 2. 准备两个工具，只为让报文里的 tools 字段有内容
        List<Tool> tools = List.of(new WebSearchTool(new MockSearchProvider(), 3), new FetchPageTool());

        // 3. 分别渲染两种报文（不联网，纯本地拼装）
        System.out.println("============ OpenAI 兼容格式 ============");
        System.out.println(pretty(new OpenAiCompatibleClient("https://api.openai.com/v1/chat/completions",
                "", "gpt-4.1").buildRequestJson(ctx, tools)));

        System.out.println("\n============ Anthropic 原生格式 ============");
        System.out.println(pretty(new AnthropicClient("", "claude-sonnet-4-5")
                .buildRequestJson(ctx, tools)));

        // 4. 顺手把差异点列出来，免得读者自己对比
        System.out.println("""

                ============ 重点看这几处差异 ============
                1. system 在 OpenAI 里是 messages[0]，在 Anthropic 里是顶层 system 字段；
                2. 工具声明：OpenAI 用 function.parameters，Anthropic 用 input_schema；
                3. 两条工具结果：OpenAI 是两条 role=tool 消息，
                   Anthropic 必须合并成一条 role=user 消息里的两个 tool_result 块；
                4. Anthropic 强制要求 max_tokens。
                """);
    }

    /**
     * JSON 美化输出。报文里嵌套很深，不格式化基本没法看。
     *
     * @param json 原始 JSON 字符串
     * @return 带缩进的 JSON 字符串
     */
    private static String pretty(String json) throws Exception {
        return M.writerWithDefaultPrettyPrinter().writeValueAsString(M.readTree(json));
    }
}
