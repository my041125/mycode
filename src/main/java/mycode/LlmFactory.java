package mycode;

import java.util.Arrays;

/**
 * 模型客户端工厂：负责回答「用哪家、地址是什么、密钥从哪来」这三个问题。
 *
 * 配置优先级（从高到低）：
 *   1. 命令行参数   --provider= --base-url= --api-key= --model= --protocol=
 *   2. 环境变量     LLM_PROVIDER / LLM_BASE_URL / LLM_API_KEY / MODEL
 *   3. 配置文件     agent.properties（项目根目录，见 agent.properties.example）
 *   4. 内置预设     见下面的 Provider 枚举
 *
 * 最常见的用法：在 agent.properties 里填三行，然后 run.bat --real 就行了。
 */
public class LlmFactory {

    public enum Provider {
        //           命令行名      endpoint                                                        密钥环境变量              默认模型
        DASHSCOPE("dashscope", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "DASHSCOPE_API_KEY", "qwen3-max", false),
        OPENAI("openai", "https://api.openai.com/v1/chat/completions", "OPENAI_API_KEY", "gpt-4.1", false),
        DEEPSEEK("deepseek", "https://api.deepseek.com/v1/chat/completions", "DEEPSEEK_API_KEY", "deepseek-chat", false),
        MOONSHOT("moonshot", "https://api.moonshot.cn/v1/chat/completions", "MOONSHOT_API_KEY", "kimi-k2-0905-preview", false),
        ZHIPU("zhipu", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "ZHIPU_API_KEY", "glm-4.6", false),
        OLLAMA("ollama", "http://localhost:11434/v1/chat/completions", null, "qwen2.5:7b", false),
        ANTHROPIC("anthropic", "https://api.anthropic.com/v1/messages", "ANTHROPIC_API_KEY", "claude-sonnet-4-5", true),
        /** 自定义中转/私有部署：必须自己给 baseUrl 和 model */
        CUSTOM("custom", null, null, null, false);

        public final String cli;
        public final String endpoint;
        public final String keyEnv;
        public final String defaultModel;
        public final boolean anthropicProtocol;

        Provider(String cli, String endpoint, String keyEnv, String defaultModel, boolean anthropicProtocol) {
            this.cli = cli;
            this.endpoint = endpoint;
            this.keyEnv = keyEnv;
            this.defaultModel = defaultModel;
            this.anthropicProtocol = anthropicProtocol;
        }

        static Provider of(String name) {
            for (Provider p : values()) {
                if (p.cli.equalsIgnoreCase(name)) return p;
            }
            throw new IllegalArgumentException("不支持的 provider: " + name + "。可选: "
                    + Arrays.stream(values()).map(p -> p.cli).toList());
        }
    }

    /** 解析后的最终配置，打印出来方便排查 401 / 404 */
    public record Resolved(String provider, String endpoint, String apiKey, String model,
                           boolean anthropic, int maxTokens) {
    }

    /**
     * 解析最终配置，四条来源按优先级合并。
     *
     * 这个过程刻意做成「纯函数、不发请求」，所以 --config 可以零成本自检：
     * 打印出来的 endpoint 就是程序真正要请求的地址，401/404 一眼就能定位。
     *
     * @param args 命令行参数
     * @return 解析后的完整配置
     */
    public static Resolved resolve(String[] args) {
        String providerArg = pick(args, "--provider", "LLM_PROVIDER", null);
        String providerFile = AgentConfig.get("provider");
        String providerName = providerArg != null ? providerArg : (providerFile != null ? providerFile : "dashscope");
        Provider p = Provider.of(providerName);

        // 命令行显式指定了另一家服务商时，配置文件里的 baseUrl / model 属于「旧的那家」，
        // 必须忽略，否则会出现 provider=openai 但 endpoint 还是 dashscope 的错配
        // （key 是通用配置，保留不动，用户仍可用 --api-key 覆盖）。
        boolean providerSwitched = providerArg != null && providerFile != null
                && !providerArg.equalsIgnoreCase(providerFile);

        // baseUrl：命令行 > 环境变量 > 配置文件（同服务商时才用）> 预设
        String baseUrl = pick(args, "--base-url", "LLM_BASE_URL", null);
        if (baseUrl == null && !providerSwitched) {
            baseUrl = AgentConfig.get("baseUrl");
        }
        if (baseUrl == null) baseUrl = p.endpoint;
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("""
                    provider=custom 必须提供 baseUrl。三种写法任选其一：
                      1) 命令行:  run.bat --real --provider=custom --base-url=https://你的地址/v1 --api-key=sk-xxx
                      2) 环境变量: set LLM_BASE_URL=https://你的地址/v1
                      3) 配置文件: 在 agent.properties 里写 baseUrl=https://你的地址/v1
                    """);
        }

        // apiKey：命令行 > LLM_API_KEY > 配置文件 > 该服务商专用变量
        String apiKey = pick(args, "--api-key", "LLM_API_KEY", null);
        if (apiKey == null) apiKey = AgentConfig.get("apiKey");
        if (apiKey == null && p.keyEnv != null) apiKey = System.getenv(p.keyEnv);
        if (apiKey == null) apiKey = "";

        String model = pick(args, "--model", "MODEL", null);
        if (model == null && !providerSwitched) {
            model = AgentConfig.get("model");
        }
        if (model == null) model = p.defaultModel;
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("provider=custom 必须指定 model，例如 --model=qwen3-max");
        }

        // 协议判定：显式指定 > 预设 > 看地址里有没有 /messages
        String proto = pick(args, "--protocol", null, null);
        boolean anthropic = proto != null
                ? proto.equalsIgnoreCase("anthropic")
                : (p.anthropicProtocol || baseUrl.contains("/messages"));

        int maxTokens = parseInt(AgentConfig.get("maxTokens"), 4096);

        return new Resolved(p.cli, normalize(baseUrl, anthropic), apiKey, model, anthropic, maxTokens);
    }

    /**
     * 把用户随手填的 baseUrl 补成完整端点 —— 这是最容易出错的地方。
     * 下面四种写法都能用，结果一致：
     *   https://xxx.com                    -> https://xxx.com/v1/chat/completions
     *   https://xxx.com/v1                 -> https://xxx.com/v1/chat/completions
     *   https://xxx.com/v1/                 -> 同上（自动去掉尾部斜杠）
     *   https://xxx.com/v1/chat/completions -> 原样使用
     */
    static String normalize(String baseUrl, boolean anthropic) {
        String b = baseUrl.strip();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        String tail = anthropic ? "/messages" : "/chat/completions";
        if (b.endsWith(tail)) return b;
        if (b.matches(".*/v\\d+$")) return b + tail;   // /v1、/v4（智谱）
        return b + "/v1" + tail;
    }

    /**
     * 按参数创建真实模型客户端，并打印一行配置摘要。
     *
     * 打印这一行是有意为之：出 401 时第一眼就能确认「key 到底有没有读到」，
     * 比在代码里打断点快得多。key 会打码，不会泄露。
     */
    public static LlmClient create(String[] args) {
        Resolved r = resolve(args);
        System.out.printf("[模型] provider=%s | model=%s | endpoint=%s | key=%s%n",
                r.provider(), r.model(), r.endpoint(), mask(r.apiKey()));
        return r.anthropic()
                ? new AnthropicClient(r.apiKey(), r.model(), r.endpoint(), r.maxTokens())
                : new OpenAiCompatibleClient(r.endpoint(), r.apiKey(), r.model());
    }

    /** 不带 --real 时根本不调用，这里只在启动时打印一行，用来确认配置有没有生效 */
    static String mask(String key) {
        if (key == null || key.isBlank()) return "(未设置)";
        if (key.length() <= 8) return "****";
        return key.substring(0, 3) + "****" + key.substring(key.length() - 4);
    }

    /**
     * 按优先级取一个配置值：先找命令行参数（--name=value），再找环境变量，最后用默认值。
     *
     * @param args    命令行参数
     * @param argName 参数名，例如 --provider
     * @param envName 环境变量名，可为 null 表示不查环境变量
     * @param def     默认值
     */
    private static String pick(String[] args, String argName, String envName, String def) {
        for (String a : args) {
            if (a.startsWith(argName + "=")) {
                String v = a.substring(argName.length() + 1).strip();
                if (!v.isEmpty()) return v;
            }
        }
        if (envName != null) {
            String v = System.getenv(envName);
            if (v != null && !v.isBlank()) return v.strip();
        }
        return def;
    }

    /** 解析整数配置，解析失败就用默认值，不让一个写错的值导致启动失败 */
    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 工厂类，禁止实例化 */
    private LlmFactory() {
    }
}
