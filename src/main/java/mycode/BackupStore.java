package mycode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 多版本备份仓库。
 *
 * 早期实现是「改之前复制一份 文件名.bak」，问题很直接：
 * 连着改两次，第一版的原始内容就永久丢了 —— 而「连改两次」恰恰是最常见的场景。
 *
 * 这里改成集中式快照仓库，三个好处：
 *   1. 每个文件保留最近 20 个版本，可以一层层往回退；
 *   2. 快照统一放在项目下的 .mycode/backups/，不污染源码目录
 *      （以前 .bak 会和源码挤在一起，还容易被误提交）；
 *   3. 快照路径里带着原文件的相对路径，所以「恢复」不需要额外记录映射关系。
 *
 * 目录结构：
 *   .mycode/backups/samples/TicketService.java/20261021-165812-123.bak
 *                    └────── 原始相对路径 ──────┘ └── 时间戳 ──┘
 */
public class BackupStore {

    /** 每个文件最多保留的版本数 */
    private static final int KEEP_PER_FILE = 20;

    /** 时间戳格式。Windows 文件名不允许冒号，所以用短横线分隔 */
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    /** 快照文件后缀 */
    private static final String SUFFIX = ".bak";

    /** 项目根目录 */
    private final Path root;

    /** 快照根目录：<项目>/.mycode/backups */
    private final Path backupRoot;

    public BackupStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.backupRoot = this.root.resolve(".mycode").resolve("backups");
    }

    /**
     * 保存一份快照（在写入新内容之前调用）。
     *
     * @param file 要备份的文件，必须已存在
     * @return 快照文件路径
     */
    public Path save(Path file) throws IOException {
        Path abs = file.toAbsolutePath().normalize();
        if (!abs.startsWith(root)) {
            throw new IllegalArgumentException("拒绝备份项目目录之外的文件: " + file);
        }
        byte[] raw = Files.readAllBytes(abs);

        // 用原文件的相对路径当子目录，这样从快照路径就能反推出原文件位置
        Path dir = backupRoot.resolve(root.relativize(abs).toString());
        Files.createDirectories(dir);

        String stamp = LocalDateTime.now().format(FMT);
        Path target = dir.resolve(stamp + SUFFIX);
        // 同一毫秒内连续两次修改（自动化的连续编辑很常见）会撞名，加序号兜底
        int seq = 1;
        while (Files.exists(target)) {
            target = dir.resolve(stamp + "-" + (seq++) + SUFFIX);
        }
        Files.write(target, raw);

        prune(dir);
        return target;
    }

    /**
     * 把某个文件恢复到最新的快照，并消耗掉这个快照（这样连续 undo 能一退再退）。
     *
     * @param file 目标文件；传 null 表示「全局最近一次修改过的文件」
     * @return 实际被恢复的文件路径
     * @throws IllegalStateException 没有可用快照时
     */
    public Path restore(Path file) throws IOException {
        Path snapshot = newest(file)
                .orElseThrow(() -> new IllegalStateException("没有可用的历史版本"));
        Path target = originalOf(snapshot);

        Files.createDirectories(target.getParent());
        Files.copy(snapshot, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(snapshot);
        return target;
    }

    /**
     * 找出最新的快照。
     *
     * @param file 限定文件；null 表示在整个仓库里找最近一个
     */
    public Optional<Path> newest(Path file) throws IOException {
        if (!Files.isDirectory(backupRoot)) return Optional.empty();

        if (file != null) {
            Path dir = backupRoot.resolve(root.relativize(file.toAbsolutePath().normalize()).toString());
            return listSnapshots(dir).stream().findFirst();
        }
        // 全局模式：文件名是固定宽度的「日期-时间-毫秒」，直接按路径倒序就是时间倒序
        try (Stream<Path> walk = Files.walk(backupRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(SUFFIX))
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        }
    }

    /** 某个文件的全部历史版本，新的在前 */
    public List<Path> historyOf(Path file) throws IOException {
        Path dir = backupRoot.resolve(root.relativize(file.toAbsolutePath().normalize()).toString());
        return listSnapshots(dir);
    }

    /**
     * 全局最近若干条快照，用于 /history 展示。
     *
     * @return 每条包含：恢复到哪个文件、备份时间、大小
     */
    public List<Snapshot> recent(int limit) throws IOException {
        List<Snapshot> out = new ArrayList<>();
        if (!Files.isDirectory(backupRoot)) return out;

        List<Path> all = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(backupRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(SUFFIX))
                    .forEach(all::add);
        }
        all.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());

        for (Path p : all) {
            if (out.size() >= limit) break;
            String name = p.getFileName().toString();
            String stamp = name.substring(0, Math.min(17, name.length()));
            out.add(new Snapshot(originalOf(p), stamp, Files.size(p)));
        }
        return out;
    }

    /** 快照条目，仅用于展示 */
    public record Snapshot(Path file, String stamp, long sizeBytes) {
    }

    // ---------------- 内部工具 ----------------

    /** 列出目录下的快照，按文件名倒序（文件名即时间序） */
    private List<Path> listSnapshots(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(SUFFIX))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        }
    }

    /** 从快照路径反推原始文件路径：去掉 backupRoot 前缀和最后一段文件名 */
    private Path originalOf(Path snapshot) {
        Path relativeDir = backupRoot.relativize(snapshot).getParent();
        return root.resolve(relativeDir.toString()).normalize();
    }

    /** 只保留最近 KEEP_PER_FILE 个版本，避免仓库无限膨胀 */
    private void prune(Path dir) throws IOException {
        List<Path> all = listSnapshots(dir);
        for (int i = KEEP_PER_FILE; i < all.size(); i++) {
            Files.deleteIfExists(all.get(i));
        }
    }

    /** 快照根目录，展示用 */
    public Path backupRoot() {
        return backupRoot;
    }
}
