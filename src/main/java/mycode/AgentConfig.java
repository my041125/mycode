package mycode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读取项目根目录的 agent.properties（可选）。
 *
 * 优先级从高到低：命令行参数 > 环境变量 > agent.properties > 内置预设
 * 之所以保留文件这一层，是因为 Windows 下改环境变量要重开终端，调试时很烦。
 *
 * 文件格式极简，一行一个 key=value，# 开头是注释，不引第三方库：
 *   provider=dashscope
 *   baseUrl=https://dashscope.aliyuncs.com/compatible-mode/v1
 *   apiKey=sk-xxxxxxxx
 *   model=qwen3-max
 */
public class AgentConfig {

    /** 配置文件解析结果。类加载时读一次，之后只读不写，省掉每次调用都碰磁盘 */
    private static final Map<String, String> FILE = load();

    /**
     * 取配置项。
     *
     * @param key 配置名，例如 baseUrl、apiKey、model
     * @return 去掉首尾空白后的值；没配或配了空串都返回 null，方便调用方用「是否为 null」判断
     */
    public static String get(String key) {
        String v = FILE.get(key);
        return v == null || v.isBlank() ? null : v.strip();
    }

    /** 是否成功读到了配置文件 —— 只用于 --config 自检时提示来源 */
    public static boolean hasFile() {
        return !FILE.isEmpty();
    }

    /**
     * 解析配置文件。任何异常都不外抛：配置文件读不了就当没有，
     * 不能让一个格式写错的文本文件阻断整个程序启动。
     */
    private static Map<String, String> load() {
        Map<String, String> map = new LinkedHashMap<>();
        // 允许用 -Dagent.config=路径 指定别的位置，默认取运行目录下的 agent.properties
        Path path = Path.of(System.getProperty("agent.config", "agent.properties"));
        if (!Files.isRegularFile(path)) {
            // 在别的目录里执行 mycode 时，运行目录下没有配置文件，
            // 退回到安装目录去找（启动脚本会传 -Dmycode.home）
            String home = System.getProperty("mycode.home");
            if (home != null && !home.isBlank()) {
                path = Path.of(home, "agent.properties");
            }
        }
        if (!Files.isRegularFile(path)) {
            return map;
        }
        try {
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                // 跳过空行与两种注释写法
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
                // 只按第一个等号切分，保证值里可以出现等号（例如带参数的 URL）
                int i = line.indexOf('=');
                if (i <= 0) continue;
                map.put(line.substring(0, i).strip(), line.substring(i + 1).strip());
            }
        } catch (IOException ignore) {
            // 读失败就返回空表，上层会退回到环境变量和内置预设
        }
        return map;
    }

    /** 工具类，禁止实例化 */
    private AgentConfig() {
    }
}
