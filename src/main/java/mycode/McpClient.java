package mycode;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP（Model Context Protocol）stdio 客户端。
 *
 * MCP 是 2026 年外部工具接入的事实标准：Claude Code、Codex、Gemini CLI 全支持。
 * 接上它，数据库、浏览器、GitHub 等现成工具生态直接可用，不用自己挨个写工具。
 *
 * 工作方式：拉起一个 MCP 服务子进程，在它的 stdin/stdout 上走 JSON-RPC
 * （换行分隔），stderr 是服务自己的日志（重定向到文件，不污染终端）：
 *   initialize        → 握手，声明客户端信息
 *   notifications/initialized
 *   tools/list        → 拿到工具清单（name / description / inputSchema）
 *   tools/call        → 执行某个工具，返回 content[] 文本块
 *
 * 读取由独立守护线程完成，请求-响应用 CompletableFuture 按 id 配对；
 * 单请求 30 秒超时，服务挂掉时未完成请求超时失败，由 ToolRegistry 转成
 * ERROR observation，主循环不受影响。
 * @author my
 */
public class McpClient {

    private static final ObjectMapper M = new ObjectMapper();

    /** 单个请求的超时秒数。MCP 工具多为本地操作，30 秒已很宽裕 */
    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    /** 服务名（mcp.json 里的 key），工具名前缀用它 */
    private final String serverName;

    private final Process process;
    private final BufferedWriter stdin;

    /** 等待响应的请求：id -> future。读线程按 id 配对后取走 */
    private final Map<Integer, CompletableFuture<Map<String, Object>>> pending =
            new ConcurrentHashMap<>();

    private final AtomicInteger nextId = new AtomicInteger(1);

    /** 服务声明的工具定义清单（tools/list 的结果） */
    private List<Map<String, Object>> toolDefs = List.of();

    private McpClient(String serverName, String command, List<String> args) throws Exception {
        this.serverName = serverName;

        // stderr 重定向到文件：服务日志可见可排查，又不和主界面抢输出
        Path logFile = Path.of(".mycode", "mcp-" + serverName + ".log").toAbsolutePath();
        if (logFile.getParent() != null) {
            Files.createDirectories(logFile.getParent());
        }

        ProcessBuilder pb = new ProcessBuilder();
        pb.command(buildCommand(command, args));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        this.process = pb.start();

        this.stdin = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        Thread reader = new Thread(this::readLoop, "mcp-" + serverName);
        reader.setDaemon(true);
        reader.start();

        initialize();
        listTools();
    }

    /**
     * 校验并组装命令行。
     *
     * 信任边界：command 与 args 只来自用户手写的 .mycode/mcp.json，
     * 和 agent.properties 里的 apiKey 属于同一信任级别（操作者本机配置），
     * 模型在运行期既读不到也改不了它。这里仍做一层防御：
     * 可执行名必须是单一段（不含空白与 shell 元字符），参数走参数列表、
     * 不经任何 shell —— 等价于 subprocess.run([...], shell=False)。
     */
    private static List<String> buildCommand(String command, List<String> args) {
        if (command == null || command.isBlank()
                || command.matches(".*[\\s&|;<>`$\"'].*")) {
            throw new IllegalArgumentException(
                    "MCP command 必须是单个可执行名（不含空白与 shell 元字符）: " + command);
        }
        List<String> cmdline = new ArrayList<>();
        cmdline.add(command);
        cmdline.addAll(args);
        return cmdline;
    }

    /**
     * 启动项目 .mycode/mcp.json 里配置的所有服务。
     * 没有配置文件返回空表；单个服务失败打一行警告继续，不阻断启动。
     *
     * 配置格式与 Claude Code 一致：
     *   {"mcpServers": {"名字": {"command": "可执行文件", "args": ["参数", "..."]}}}
     */
    @SuppressWarnings("unchecked")
    public static List<McpClient> startAll(Path projectRoot) {
        Path cfg = projectRoot.toAbsolutePath().normalize()
                .resolve(".mycode").resolve("mcp.json");
        if (!Files.isRegularFile(cfg)) return List.of();

        List<McpClient> started = new ArrayList<>();
        try {
            Map<String, Object> root = M.readValue(Files.readString(cfg, StandardCharsets.UTF_8), Map.class);
            if (!(root.get("mcpServers") instanceof Map<?, ?> servers)) return List.of();
            for (Map.Entry<?, ?> e : servers.entrySet()) {
                String name = String.valueOf(e.getKey());
                if (!(e.getValue() instanceof Map<?, ?> spec)) continue;
                String command = String.valueOf(spec.get("command"));
                List<String> args = new ArrayList<>();
                if (spec.get("args") instanceof List<?> list) {
                    for (Object o : list) args.add(String.valueOf(o));
                }
                try {
                    started.add(new McpClient(name, command, args));
                } catch (Exception ex) {
                    System.out.println(Ui.dim("⚠ MCP 服务 " + name + " 启动失败："
                            + ex.getMessage() + "（日志: .mycode/mcp-" + name + ".log）"));
                }
            }
        } catch (Exception ignore) {
            // 配置坏了就当没配，不能让一个 JSON 挡住启动
        }
        return started;
    }

    /** 读循环：逐行收 JSON-RPC 响应，交给按 id 等待的调用方 */
    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                handle(line);
            }
        } catch (IOException ignore) {
            // 进程退出或流关闭；未完成的请求由 request 的超时兜底
        }
    }

    /** 一行 JSON-RPC：带 id 的响应完成对应 future；通知与杂散输出忽略 */
    private void handle(String line) {
        try {
            Map<String, Object> msg = M.readValue(line, Map.class);
            if (msg.get("id") instanceof Number n
                    && pending.containsKey(n.intValue())) {
                CompletableFuture<Map<String, Object>> f = pending.remove(n.intValue());
                if (f != null) f.complete(msg);
            }
        } catch (Exception ignore) {
            // 非 JSON 行（服务器的杂散输出）忽略
        }
    }

    /** 发一个请求并等响应；错误响应抛异常，超时 30 秒 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> request(String method, Map<String, Object> params) throws Exception {
        int id = nextId.getAndIncrement();
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pending.put(id, future);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", id);
        payload.put("method", method);
        payload.put("params", params == null ? Map.of() : params);
        write(M.writeValueAsString(payload));

        Map<String, Object> resp = future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (resp.get("error") instanceof Map<?, ?> err) {
            throw new IllegalStateException("MCP " + serverName + " 返回错误: " + err.get("message"));
        }
        Object result = resp.get("result");
        return result instanceof Map ? (Map<String, Object>) result : Map.of();
    }

    private synchronized void write(String json) throws IOException {
        stdin.write(json);
        stdin.newLine();
        stdin.flush();
    }

    /** 握手：initialize + initialized 通知。协议版本写死一个双方都认的 */
    private void initialize() throws Exception {
        request("initialize", Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "mycode", "version", "1.4")));
        // 通知没有 id，服务端不会回包
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("method", "notifications/initialized");
        write(M.writeValueAsString(payload));
    }

    /** 拉取该服务的工具清单 */
    @SuppressWarnings("unchecked")
    private void listTools() throws Exception {
        Map<String, Object> result = request("tools/list", Map.of());
        if (result.get("tools") instanceof List<?> list) {
            List<Map<String, Object>> defs = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map) defs.add((Map<String, Object>) o);
            }
            toolDefs = defs;
        }
    }

    /** 服务名，McpTool 拼前缀用 */
    public String serverName() {
        return serverName;
    }

    /** 该服务声明的工具定义（name / description / inputSchema） */
    public List<Map<String, Object>> toolDefs() {
        return toolDefs;
    }

    /**
     * 调一个远端工具，拼接 text 内容块作为结果。
     * isError 时按约定抛异常，由 ToolRegistry 转成 ERROR observation。
     */
    public String callTool(String toolName, Map<String, Object> arguments) throws Exception {
        Map<String, Object> result = request("tools/call", Map.of(
                "name", toolName,
                "arguments", arguments == null ? Map.of() : arguments));
        StringBuilder sb = new StringBuilder();
        if (result.get("content") instanceof List<?> blocks) {
            for (Object o : blocks) {
                if (o instanceof Map<?, ?> b && "text".equals(b.get("type"))) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(b.get("text"));
                }
            }
        }
        if (Boolean.TRUE.equals(result.get("isError"))) {
            throw new IllegalStateException(sb.length() == 0 ? "工具执行失败" : sb.toString());
        }
        return sb.toString();
    }

    /** 结束服务进程。注册进 shutdown hook，JVM 退出时统一调用 */
    public void close() {
        process.destroy();
    }
}
