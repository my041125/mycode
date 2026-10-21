package mycode;

/**
 * 阿里云百炼（DashScope）兼容模式客户端。
 *
 * 它走的就是 OpenAI 协议（/compatible-mode/v1/chat/completions），
 * 所以这里一行额外逻辑都不需要，只负责把默认地址填好。
 * 这正是「参数化 endpoint」带来的好处：接一家新服务商往往只是加一个子类或一个预设。
 *
 * 使用前注意两点：
 *   1. qwen3 系列默认开「思考模式」，调用工具会明显变慢，父类里已自动关掉；
 *   2. 同一个 key 在兼容模式下的额度与原生 API 共享，别以为换了个入口就不花钱。
 */
public class DashScopeClient extends OpenAiCompatibleClient {

    /** 兼容模式的标准端点 */
    private static final String DEFAULT_ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    /**
     * 最常用构造。
     *
     * @param apiKey 百炼控制台申请的 key（sk- 开头）
     * @param model  模型名，例如 qwen3-max、qwen-plus
     */
    public DashScopeClient(String apiKey, String model) {
        super(DEFAULT_ENDPOINT, apiKey, model);
    }

    /**
     * 允许自定义端点的构造，用于走代理或私有网关。
     *
     * @param apiKey   密钥
     * @param model    模型名
     * @param endpoint 完整请求地址
     */
    public DashScopeClient(String apiKey, String model, String endpoint) {
        super(endpoint, apiKey, model);
    }
}
