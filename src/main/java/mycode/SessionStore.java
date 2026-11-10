package mycode;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 会话持久化：把整场对话存成 JSONL 文件，--continue 时读回最近一份。
 *
 * 没有它，退出即失忆；跨天的工作只能从头再讲一遍背景。
 * Claude Code 的 --continue / --resume、Gemini 的会话分支都是这个能力。
 *
 * 文件格式（.mycode/sessions/session-<时间戳>.jsonl）：
 *   第 1 行  {"type":"meta","turns":..,"promptTokens":..,...}
 *   其后每行  {"type":"msg","message":{role, content, ...}}
 *
 * 上下文本来就是 OpenAI 协议的裸 Map，序列化零转换；恢复时原样塞回。
 * 每轮问答后全量覆盖写，进程被杀最多丢当前这一轮。
 * 持久化失败一律静默 —— 它是体验增强，绝不是关键路径。
 * @author my
 */
public class SessionStore {

    private static final ObjectMapper M = new ObjectMapper();

    /** 存档目录（相对项目根） */
    private final Path dir;

    /** 本次会话的存档文件。构造时定名，之后每次覆盖写同一个文件 */
    private final Path file;

    public SessionStore(Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        this.dir = root.resolve(".mycode").resolve("sessions");
        this.file = dir.resolve("session-" + stamp() + ".jsonl");
    }

    /** 当前时间戳，用作文件名。秒级精度，按名字排序即按时间排序 */
    private static String stamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    /** 保存当前会话快照（全量覆盖写） */
    public void save(AgentContext ctx, int turns, Usage usage) {
        try {
            Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("type", "meta");
            meta.put("turns", turns);
            meta.put("promptTokens", usage.promptTokens());
            meta.put("completionTokens", usage.completionTokens());
            meta.put("cacheReadTokens", usage.cacheReadTokens());
            meta.put("savedAt", stamp());
            sb.append(M.writeValueAsString(meta)).append('\n');
            for (Map<String, Object> msg : ctx.messages()) {
                sb.append(M.writeValueAsString(Map.of("type", "msg", "message", msg))).append('\n');
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignore) {
            // 存不了就算了，对话继续
        }
    }

    /** 恢复出来的会话内容 */
    public record Restored(AgentContext ctx, int turns, Usage usage) {
    }

    /** 找到最近的存档并恢复；没有任何存档或存档损坏时返回 null（调用方开新会话） */
    public Restored loadLatest() {
        Path latest = findLatest();
        if (latest == null) return null;
        try {
            List<String> lines = Files.readAllLines(latest, StandardCharsets.UTF_8);
            int turns = 0;
            Usage usage = Usage.ZERO;
            List<Map<String, Object>> messages = new ArrayList<>();
            for (String line : lines) {
                if (line.isBlank()) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> rec = M.readValue(line, Map.class);
                if ("meta".equals(rec.get("type"))) {
                    turns = ((Number) rec.get("turns")).intValue();
                    usage = new Usage(
                            ((Number) rec.getOrDefault("promptTokens", 0)).intValue(),
                            ((Number) rec.getOrDefault("completionTokens", 0)).intValue(),
                            ((Number) rec.getOrDefault("cacheReadTokens", 0)).intValue());
                } else if (rec.get("message") instanceof Map<?, ?> msg) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) msg;
                    messages.add(m);
                }
            }
            if (messages.isEmpty()) return null;
            return new Restored(AgentContext.restore(messages), turns, usage);
        } catch (Exception e) {
            // 存档损坏就当没有，开新会话比报错卡死好
            return null;
        }
    }

    /** 目录里时间戳最新的存档文件；没有返回 null */
    private Path findLatest() {
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("session-") && name.endsWith(".jsonl");
                    })
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
