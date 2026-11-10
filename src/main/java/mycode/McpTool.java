package mycode;

import java.util.Map;

/**
 * MCP 远端工具的本地适配器：把服务端声明的工具包装成 mycode 的 Tool 接口。
 *
 * 名字加 mcp_<服务>_ 前缀，防止与本地工具或其它服务的工具撞名；
 * inputSchema 服务端本来就是标准 JSON Schema，直接透传 ——
 * Tool.parameters() 的契约就是 JSON Schema Map，两边天然对齐。
 * @author my
 */
public class McpTool implements Tool {

    private final McpClient client;

    /** 服务端的原始工具名，tools/call 时要用 */
    private final String remoteName;

    private final String description;

    private final Map<String, Object> schema;

    public McpTool(McpClient client, Map<String, Object> def) {
        this.client = client;
        this.remoteName = String.valueOf(def.get("name"));
        Object desc = def.get("description");
        this.description = "[MCP:" + client.serverName() + "] "
                + (desc == null ? "(无描述)" : String.valueOf(desc));
        this.schema = def.get("inputSchema") instanceof Map<?, ?> s
                ? cast(s)
                : Map.of("type", "object", "properties", Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    @Override
    public String name() {
        // 只留小写字母数字下划线，防止服务端的工具名带特殊字符
        String server = client.serverName().toLowerCase().replaceAll("[^a-z0-9_]", "_");
        String tool = remoteName.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        return "mcp_" + server + "_" + tool;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> parameters() {
        return schema;
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        return client.callTool(remoteName, args);
    }
}
