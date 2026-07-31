package com.mcqqbridge.stats;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据采集监听器。独立于桥接模式：无论 chat/full 都始终采集。
 * 轨迹按固定时间间隔采样，并过滤坐标未变化的点（挂机不产生冗余点，
 * 停留时长由相邻点的时间间隔表达）。
 */
public class PlayerTracker implements Listener {

    private final JavaPlugin plugin;
    private final long trailIntervalMs;
    private final long stayThresholdMs;
    private final StatisticsSnapshot statistics = new StatisticsSnapshot();
    private final Map<String, DailyRecord> records = new ConcurrentHashMap<>();
    private final Map<UUID, TrailState> trailState = new ConcurrentHashMap<>();
    private final Map<UUID, Long> joinTime = new ConcurrentHashMap<>();

    private record TrailState(long lastRecordT, int x, int y, int z) {}

    public PlayerTracker(JavaPlugin plugin, long trailIntervalMs, long stayThresholdMs) {
        this.plugin = plugin;
        this.trailIntervalMs = trailIntervalMs;
        this.stayThresholdMs = stayThresholdMs;
    }

    private DailyRecord todayRecord() {
        return records.computeIfAbsent(LocalDate.now().toString(), DailyRecord::new);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Location loc = player.getLocation();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();

        TrailState st = trailState.get(id);
        if (st != null && now - st.lastRecordT < trailIntervalMs) {
            return; // 距上次记点不足间隔，节流
        }
        if (st != null && st.x == bx && st.y == by && st.z == bz) {
            return; // 坐标未变（挂机），不记点，也不刷新 lastRecordT 以累积停留时长
        }
        todayRecord().addTrail(player.getName(), bx, by, bz, now, loc.getWorld().getName());
        trailState.put(id, new TrailState(now, bx, by, bz));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        joinTime.put(id, now);
        statistics.recordBaseline(player);
        trailState.remove(id); // 进服首个移动点立即记录
        Location loc = player.getLocation();
        todayRecord().addBreak(player.getName(), "JOIN", loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), now);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long jt = joinTime.remove(id);
        if (jt != null) {
            todayRecord().addPlaytime(player.getName(), now - jt);
        }
        Map<Statistic, Integer> delta = statistics.computeAndRemoveDelta(player);
        DailyRecord record = todayRecord();
        delta.forEach((s, v) -> record.addStat(player.getName(), s.name(), v));
        trailState.remove(id);
        Location loc = player.getLocation();
        String world = loc.getWorld().getName();
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        // 补记退出位置轨迹点，避免轨迹线末端与 QUIT 标记之间割裂
        record.addTrail(player.getName(), bx, by, bz, now, world);
        record.addBreak(player.getName(), "QUIT", world, bx, by, bz, now);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        Location loc = player.getLocation();
        String text = event.deathMessage() == null
                ? ""
                : PlainTextComponentSerializer.plainText().serialize(event.deathMessage());
        long now = System.currentTimeMillis();
        DailyRecord record = todayRecord();
        String world = loc.getWorld().getName();
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        // 补记死亡位置轨迹点，使轨迹线延伸到红X 处
        record.addTrail(player.getName(), bx, by, bz, now, world);
        record.addEvent(player.getName(), "death", world, bx, by, bz, now, text);
        record.addBreak(player.getName(), "DEATH", world, bx, by, bz, now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                || cause == PlayerTeleportEvent.TeleportCause.END_PORTAL
                || cause == PlayerTeleportEvent.TeleportCause.END_GATEWAY
                || cause == PlayerTeleportEvent.TeleportCause.COMMAND
                || cause == PlayerTeleportEvent.TeleportCause.PLUGIN) {
            Location loc = event.getFrom();
            todayRecord().addBreak(player.getName(), "TP", loc.getWorld().getName(),
                    loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        var display = event.getAdvancement().getDisplay();
        if (display == null || !display.doesAnnounceToChat()) {
            return; // 只记游戏内公告的真成就，与桥接转发口径一致
        }
        Player player = event.getPlayer();
        Location loc = player.getLocation();
        String title = PlainTextComponentSerializer.plainText().serialize(display.title());
        long now = System.currentTimeMillis();
        todayRecord().addEvent(player.getName(), "advancement", loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), now, title);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        String name = event.getPlayer().getName();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        long now = System.currentTimeMillis();
        todayRecord().addChat(name, now, text);
    }

    // ---- 供日报结算/落盘使用 ----

    public DailyRecord getRecord(String date) {
        return records.get(date);
    }

    public DailyRecord getTodayRecord() {
        return todayRecord();
    }

    public Collection<String> recordDates() {
        return records.keySet();
    }

    public void removeRecord(String date) {
        records.remove(date);
    }

    /** 在线玩家自进服以来尚未结算的统计增量（结算时临时合并展示，不写入 record）。 */
    public Map<Statistic, Integer> currentOnlineDelta(Player player) {
        return statistics.currentDelta(player);
    }

    /** 在线玩家本次进服的时间戳，用于结算当前在线时长段。 */
    public Long getJoinTime(UUID playerId) {
        return joinTime.get(playerId);
    }

    public long getStayThresholdMs() {
        return stayThresholdMs;
    }
}
