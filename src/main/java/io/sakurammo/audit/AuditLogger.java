package io.sakurammo.audit;

import io.sakurammo.SakuraMMOPlugin;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 审计日志（阶段四模块 D）：关键操作（金币产出/回收、领取奖励、管理操作）
 * 追加写入 logs/audit/audit-yyyy-MM-dd.log。
 * <p>
 * 写入方式：调用方入队（无锁），异步任务每 5 秒批量刷盘——不卡主线程；
 * 停服时强制 flush。
 */
public final class AuditLogger {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SakuraMMOPlugin plugin;
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
    private Object flushTask;

    public AuditLogger(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flush, 100L, 100L);
    }

    public void stop() {
        if (flushTask instanceof org.bukkit.scheduler.BukkitTask task) {
            task.cancel();
        }
        flushTask = null;
        flush(); // 停服强制落盘
    }

    /** 记录一条审计事件（ actor / 动作 / 详情 ）。 */
    public void log(String actor, String action, String detail) {
        queue.add(String.format(Locale.ROOT, "[%s] %s | %s | %s",
                LocalDateTime.now().format(TIME), actor, action, detail));
    }

    /** 批量刷盘（异步线程执行）。 */
    public void flush() {
        if (queue.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = queue.poll()) != null) {
            sb.append(line).append(System.lineSeparator());
        }
        if (sb.isEmpty()) {
            return;
        }
        try {
            Path dir = plugin.getDataFolder().toPath().resolve("logs/audit");
            Files.createDirectories(dir);
            Path file = dir.resolve("audit-" + LocalDate.now() + ".log");
            Files.writeString(file, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            plugin.getLogger().warning("审计日志写入失败：" + ex.getMessage());
        }
    }
}
