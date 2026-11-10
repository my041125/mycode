package mycode;

import java.util.List;
import java.util.Locale;

/**
 * 权限模式：决定 agent 到底能做什么。
 *
 * 这是加装 run_command 之后必须同步引入的东西 ——
 * 一个能执行任意命令、又能改文件的 agent，如果没有任何权限边界，
 * 就等于把终端交给了一个可能产生幻觉的程序。
 *
 * 三档：
 *   read-only  只能读，不能改文件、不能执行命令（读代码、问问题）
 *   edit       可以改文件；执行命令要逐条确认（默认档，日常够用）
 *   full       全部放行（自己玩的临时目录、或者完全信任的时候）
 * @author my
 */
public enum PermissionMode {

    /** 只读：list_files / read_code / 搜索可用，编辑和执行全部拒绝 */
    READ_ONLY("read-only", "只读，不能改文件也不能执行命令"),

    /** 可编辑：改文件放行，执行命令逐条确认 */
    EDIT("edit", "可改文件；执行命令需要确认"),

    /** 全放行：不再询问 */
    FULL("full", "全部放行，不再询问");

    /** 命令行里用的名字，例如 --permission=edit */
    public final String cli;

    /** 人类可读的说明，启动横幅里展示 */
    public final String label;

    PermissionMode(String cli, String label) {
        this.cli = cli;
        this.label = label;
    }

    /**
     * 解析命令行参数里的权限模式，缺省用 EDIT。
     *
     * @param value 形如 "edit" / "read-only" / "full"，忽略大小写
     */
    public static PermissionMode parse(String value) {
        if (value == null || value.isBlank()) return EDIT;
        String v = value.strip().toLowerCase(Locale.ROOT);
        for (PermissionMode m : values()) {
            if (m.cli.equals(v)) return m;
        }
        throw new IllegalArgumentException("不支持的权限模式: " + value
                + "。可选: " + List.of("read-only", "edit", "full"));
    }
}
