package mycode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/**
 * 后台任务结果查询工具，与 run_command 的 background=true 配对使用。
 *
 * 有了它，「跑长测试 / 起 dev server」才不用阻塞整个会话：
 * 命令丢后台立即返回 id，模型继续干活，需要时来这里收结果。
 * @author my
 */
public class TaskOutputTool implements Tool {

    /** 日志返回给模型的行数与字符上限。全量日志在磁盘上，模型只看尾部 */
    private static final int LOG_LINES = 40;
    private static final int LOG_CHARS = 3000;

    @Override
    public String name() {
        return "task_output";
    }

    @Override
    public String description() {
        return "查询后台命令任务的状态与输出。任务 id 来自 run_command 传 background=true 的返回值。"
                + "运行中返回状态与最新日志尾部；已结束返回退出码与日志尾部。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Tool.schema(Map.of(
                "id", Tool.str("run_command 返回的后台任务 id，例如 b1")), "id");
    }

    @Override
    public String execute(Map<String, Object> args) {
        String id = String.valueOf(args.getOrDefault("id", ""));
        CommandTool.BackgroundTask task = CommandTool.backgroundTask(id);
        if (task == null) {
            return "ERROR: 不存在后台任务 " + id + "（id 写错、或该会话没启动过后台任务）";
        }
        String status = task.process().isAlive()
                ? "运行中（已运行 " + (System.currentTimeMillis() - task.startedAtMillis()) / 1000 + " 秒）"
                : "已退出，退出码 " + task.process().exitValue();
        return "任务 " + task.id() + " 状态: " + status
                + "\n日志尾部（.mycode/runs/" + task.id() + ".log）:\n" + tail(task.logFile());
    }

    /** 读日志末尾几行。解码规则与命令输出一致（UTF-8 优先，退回 GBK） */
    private String tail(Path logFile) {
        try {
            String text = CommandTool.decode(Files.readAllBytes(logFile)).strip();
            if (text.isEmpty()) return "(暂无输出)";
            String[] lines = text.split("\n");
            String tailText = lines.length <= LOG_LINES ? text
                    : String.join("\n", Arrays.copyOfRange(lines, lines.length - LOG_LINES, lines.length));
            return tailText.length() <= LOG_CHARS ? tailText
                    : tailText.substring(tailText.length() - LOG_CHARS) + "…";
        } catch (Exception e) {
            return "(日志读取失败: " + e.getMessage() + ")";
        }
    }
}
