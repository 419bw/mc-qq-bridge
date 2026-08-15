package com.mcqqbridge.report;

import com.mcqqbridge.config.BridgeConfig;
import com.mcqqbridge.qq.QQBotClient;
import com.mcqqbridge.stats.DailyRecord;
import com.mcqqbridge.stats.DailyRecord.PlayerSnapshot;
import com.mcqqbridge.stats.DataStore;
import com.mcqqbridge.stats.PlayerTracker;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 日报定时调度。定时触发在主线程采集在线玩家快照（Bukkit 玩家数据须主线程读取），
 * 随后切换到异步线程执行落盘、清理、底图拼接、渲染与发送，避免阻塞主线程。
 */
public class DailyReportScheduler {

    private final JavaPlugin plugin;
    private final BridgeConfig config;
    private final PlayerTracker tracker;
    private final DataStore dataStore;
    private final MapRenderer renderer;
    private final ReportFormatter formatter;
    private final QQBotClient qqClient;
    private final TerrainTileCache terrainCache;
    private final int hour;
    private final int minute;
    private final int retentionDays;
    private final Logger logger;

    private volatile boolean enabled;
    private BukkitTask task;

    public DailyReportScheduler(JavaPlugin plugin, BridgeConfig config, PlayerTracker tracker,
                                DataStore dataStore, MapRenderer renderer, ReportFormatter formatter,
                                QQBotClient qqClient, TerrainTileCache terrainCache,
                                int hour, int minute, int retentionDays, boolean enabled) {
        this.plugin = plugin;
        this.config = config;
        this.tracker = tracker;
        this.dataStore = dataStore;
        this.renderer = renderer;
        this.formatter = formatter;
        this.qqClient = qqClient;
        this.terrainCache = terrainCache;
        this.hour = hour;
        this.minute = minute;
        this.retentionDays = retentionDays;
        this.enabled = enabled;
        this.logger = plugin.getLogger();
    }

    public void start() {
        if (enabled) {
            scheduleNext();
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
        if (value) {
            scheduleNext();
        } else {
            stop();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 手动立即生成并推送当前窗口日报，并切换到新统计窗口（无视 enabled）。调用方须在主线程。 */
    public void runNow() {
        reportAndRoll();
    }

    private void scheduleNext() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.toLocalDate().atTime(hour, minute, 0);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        long ticks = Math.max(1, Duration.between(now, next).toMillis() / 50);
        task = Bukkit.getScheduler().runTaskLater(plugin, this::collectAndDispatch, ticks);
    }

    private void collectAndDispatch() {
        try {
            if (enabled) {
                reportAndRoll();
            }
        } finally {
            if (enabled) {
                scheduleNext();
            }
        }
    }

    /**
     * 主线程执行：结算在线玩家 -> 冻结本窗口 record 引用 -> 拍在线快照（结算后增量归零，
     * 只提供"谁还在线"状态，避免与 record 已结算数字双计）-> 切窗 -> 异步落盘/渲染/推送。
     * 切窗后新事件直接进新一天的 record，与异步出报互不干扰。
     */
    private void reportAndRoll() {
        tracker.settleOnline();
        DailyRecord frozen = tracker.getTodayRecord();
        List<ReportFormatter.OnlineSnapshot> online = collectOnline();
        tracker.roll();
        dispatch(frozen, online);
    }

    private List<ReportFormatter.OnlineSnapshot> collectOnline() {
        List<ReportFormatter.OnlineSnapshot> online = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            online.add(new ReportFormatter.OnlineSnapshot(
                    p.getUniqueId(), p.getName(),
                    tracker.currentOnlineDelta(p),
                    tracker.getJoinTime(p.getUniqueId())));
        }
        return online;
    }

    private void dispatch(DailyRecord record, List<ReportFormatter.OnlineSnapshot> online) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> doReport(record, online));
    }

    private void doReport(DailyRecord record, List<ReportFormatter.OnlineSnapshot> online) {
        try {
            Path file = dataStore.save(record);
            logger.info("[Report] saved " + file);
        } catch (IOException e) {
            logger.warning("[Report] save failed: " + e.getMessage());
        }

        int removed = dataStore.cleanup(retentionDays);
        if (removed > 0) {
            logger.info("[Report] cleaned " + removed + " old record file(s)");
        }
        pruneMemory();

        String gid = config.getGroupOpenId();
        if (gid.isEmpty()) {
            logger.warning("[Report] no group bound, skip push");
            return;
        }

        long now = System.currentTimeMillis();
        long stayThresholdMs = tracker.getStayThresholdMs();
        Map<String, PlayerSnapshot> snap = record.snapshotAll(stayThresholdMs);
        ReportFormatter.ReportData data = formatter.buildSummaries(snap, online, stayThresholdMs, now);
        String text = formatter.format(data, record.getDate());
        qqClient.sendGroupMessage(gid, text);

        World overworld = Bukkit.getWorlds().stream()
                .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
                .findFirst().orElse(null);
        World netherWorld = Bukkit.getWorlds().stream()
                .filter(w -> w.getEnvironment() == World.Environment.NETHER)
                .findFirst().orElse(null);

        boolean anyMap = false;
        if (overworld != null) {
            anyMap |= renderAndPush(snap, record.getDate(), gid, overworld, null);
            if (terrainCache != null) {
                logger.info("[Report] terrain tiles on disk: " + terrainCache.tileCount());
            }
        }
        if (netherWorld != null) {
            renderAndPush(snap, record.getDate(), gid, netherWorld, "（下界）");
        }
        if (!anyMap) {
            logger.info("[Report] no map generated (no tracked activity)");
        }

        if (terrainCache != null) {
            terrainCache.resetFreshMarkers();
        }
    }

    /** 渲染并推送单个世界的探索图（computeWindow -> 底图 -> 叠加 -> 推送）；无该世界活动时返回 false。 */
    private boolean renderAndPush(Map<String, PlayerSnapshot> snap, String date, String gid,
                                  World world, String titleSuffix) {
        String worldName = world.getName();
        int[] win = MapRenderer.computeWindow(snap, config.getMapPadding(), worldName);
        if (win == null) {
            return false;
        }
        BufferedImage terrain = terrainCache == null ? null
                : terrainCache.buildTerrainImage(world, win[0], win[1], win[2], win[3]);
        byte[] png = renderer.render(snap, date, terrain, win, worldName, titleSuffix);
        if (png != null) {
            qqClient.sendGroupImage(gid, png);
        }
        return true;
    }

    private void pruneMemory() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        for (String date : new ArrayList<>(tracker.recordDates())) {
            try {
                if (LocalDate.parse(date).isBefore(yesterday)) {
                    tracker.removeRecord(date);
                }
            } catch (DateTimeParseException ignored) {
            }
        }
    }
}
