package mycode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任务清单工具：把当前任务的计划写成结构化清单，做一项更新一项。
 *
 * Claude Code 的 TodoWrite、Codex 的 update_plan 都是同一个东西 ——
 * 长任务的「骨架」：模型先把计划落成清单，用户随时看到进度，
 * 模型自己也靠它对齐「现在做到哪了、还差什么」。
 *
 * 与子代理的分工：todo 管「计划与进度」，task 管「派活」。
 * 典型配合是先 todo 列出步骤，再逐项派给子代理执行。
 *
 * 清单由工具自己渲染到终端（和 ConsoleHook 一个路数），
 * 返回给模型的只有一句确认，不占 observation。
 * @author my
 */
public class TodoTool implements Tool {

    /** 一项任务：内容 + 状态 */
    public record Item(String content, String status) {
    }

    /** 当前清单。全量替换，不提供单项增删 —— 小模型对「整体重写」的理解远好于补丁式更新 */
    private List<Item> items = List.of();

    @Override
    public String name() {
        return "todo";
    }

    @Override
    public String description() {
        return "维护当前任务的可视化清单。接到多步任务先写出全部步骤（全为 pending），"
                + "开始某一步前把该步标为 in_progress，做完标为 completed。"
                + "每次调用传入完整清单（全量替换，不是追加）。"
                + "一两次工具调用就能完成的小事不要用。";
    }

    @Override
    public Map<String, Object> parameters() {
        // 数组型 schema 要手写：Tool.str/num 便捷方法只覆盖标量
        Map<String, Object> item = Map.of(
                "type", "object",
                "properties", Map.of(
                        "content", Tool.str("这一步要做什么，一句话"),
                        "status", Tool.str("pending（待办）/ in_progress（进行中）/ completed（已完成）")),
                "required", List.of("content", "status"));
        return Tool.schema(Map.of(
                        "todos", Map.of("type", "array", "items", item,
                                "description", "完整清单，每次全量替换")),
                "todos");
    }

    @Override
    public synchronized String execute(Map<String, Object> args) {
        List<Item> parsed = new ArrayList<>();
        if (args.get("todos") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String content = m.get("content") == null ? "" : String.valueOf(m.get("content")).strip();
                if (content.isEmpty()) continue;
                parsed.add(new Item(content, normalizeStatus(m.get("status"))));
            }
        }
        items = List.copyOf(parsed);
        print();

        long done = items.stream().filter(i -> "completed".equals(i.status())).count();
        return items.isEmpty()
                ? "清单已清空"
                : "OK: 清单已更新（共 " + items.size() + " 项，已完成 " + done + "）";
    }

    /** 宽容解析状态：中英文、大小写都认，认不出按待办处理 */
    private static String normalizeStatus(Object raw) {
        String s = raw == null ? "" : String.valueOf(raw).strip().toLowerCase();
        if (s.contains("progress") || s.contains("进行")) return "in_progress";
        if (s.contains("complet") || s.contains("done") || s.contains("完成") || s.contains("已")) {
            return "completed";
        }
        return "pending";
    }

    /** 渲染当前清单到终端。/todo 命令和工具执行共用这一个入口 */
    public void print() {
        System.out.println("  " + Ui.dim("任务清单："));
        if (items.isEmpty()) {
            System.out.println("  " + Ui.dim("（空）"));
            return;
        }
        for (Item i : items) {
            String mark = switch (i.status()) {
                case "completed" -> Ui.dim("☑");
                case "in_progress" -> Ui.accent("▸");
                default -> "☐";
            };
            String text = "completed".equals(i.status()) ? Ui.dim(i.content()) : i.content();
            System.out.println("  " + mark + " " + text);
        }
    }
}
