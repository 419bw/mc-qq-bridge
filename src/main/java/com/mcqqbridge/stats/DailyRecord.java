package com.mcqqbridge.stats;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单日的采集数据内存模型，线程安全。
 * 写方法按玩家粒度加锁（per-player lock = PlayerData 实例本身），
 * 使主线程的轨迹/事件写入与异步聊天线程的写入互不阻塞不同玩家。
 * 跨包读取统一通过 {@link #snapshotAll(long)} 获取不可变快照，避免直接暴露可变内部状态。
 */
public class DailyRecord {

    // 低于此 Y 坐标视为地下活动（粗略启发式，矿洞/深板岩层基本落在其下）。渲染逐点判断也读此常量。
    public static final int UNDERGROUND_Y = 40;

    public record TrailPoint(int x, int y, int z, long t, String world) {}

    public record Stay(int x, int z, long startT, long endT, long minutes) {}

    public record GameEvent(String type, String world, int x, int y, int z, long t, String text) {}

    public record ChatLine(long t, String player, String text) {}

    /** 轨迹断点：type 为 JOIN / QUIT / DEATH / TP，用于渲染时切段不连线。 */
    public record BreakPoint(String type, String world, int x, int y, int z, long t) {}

    /** 不可变玩家快照，供渲染与日报文字跨包、跨线程安全使用。 */
    public record PlayerSnapshot(
            long playtimeMs,
            Map<String, Integer> stats,
            int chatCount,
            List<TrailPoint> trail,
            List<GameEvent> events,
            List<ChatLine> chats,
            List<Stay> stays,
            List<BreakPoint> breaks,
            int minY,
            int undergroundPoints,
            int surfacePoints) {
    }

    public static final class PlayerData {
        long playtimeMs;
        final Map<String, Integer> stats = new HashMap<>();
        int chatCount;
        final List<TrailPoint> trail = new ArrayList<>();
        final List<GameEvent> events = new ArrayList<>();
        final List<ChatLine> chats = new ArrayList<>();
        final List<BreakPoint> breaks = new ArrayList<>();
        int minY = Integer.MAX_VALUE;
        int undergroundPoints;
        int surfacePoints;
    }

    private final String date;
    private final Map<String, PlayerData> players = new ConcurrentHashMap<>();

    public DailyRecord(String date) {
        this.date = date;
    }

    public String getDate() {
        return date;
    }

    private PlayerData player(String name) {
        return players.computeIfAbsent(name, n -> new PlayerData());
    }

    public void addTrail(String name, int x, int y, int z, long t, String world) {
        PlayerData d = player(name);
        synchronized (d) {
            d.trail.add(new TrailPoint(x, y, z, t, world));
            if (y < UNDERGROUND_Y) {
                d.undergroundPoints++;
            } else {
                d.surfacePoints++;
            }
            if (y < d.minY) {
                d.minY = y;
            }
        }
    }

    public void addBreak(String name, String type, String world, int x, int y, int z, long t) {
        PlayerData d = player(name);
        synchronized (d) {
            d.breaks.add(new BreakPoint(type, world, x, y, z, t));
        }
    }

    public void addEvent(String name, String type, String world, int x, int y, int z, long t, String text) {
        PlayerData d = player(name);
        synchronized (d) {
            d.events.add(new GameEvent(type, world, x, y, z, t, text));
        }
    }

    public void addChat(String name, long t, String text) {
        PlayerData d = player(name);
        synchronized (d) {
            d.chats.add(new ChatLine(t, name, text));
            d.chatCount++;
        }
    }

    public void addPlaytime(String name, long ms) {
        PlayerData d = player(name);
        synchronized (d) {
            d.playtimeMs += ms;
        }
    }

    public void addStat(String name, String statName, int delta) {
        PlayerData d = player(name);
        synchronized (d) {
            d.stats.merge(statName, delta, Integer::sum);
        }
    }

    public boolean isEmpty() {
        return players.isEmpty();
    }

    /** 返回所有玩家的原子快照（保序）。每个玩家在其锁内一次性拷贝全部字段。 */
    public Map<String, PlayerSnapshot> snapshotAll(long stayThresholdMs) {
        Map<String, PlayerSnapshot> out = new LinkedHashMap<>();
        for (Map.Entry<String, PlayerData> e : players.entrySet()) {
            PlayerData d = e.getValue();
            synchronized (d) {
                out.put(e.getKey(), new PlayerSnapshot(
                        d.playtimeMs,
                        Map.copyOf(d.stats),
                        d.chatCount,
                        List.copyOf(d.trail),
                        List.copyOf(d.events),
                        List.copyOf(d.chats),
                        computeStaysLocked(d, stayThresholdMs),
                        List.copyOf(d.breaks),
                        d.minY == Integer.MAX_VALUE ? 0 : d.minY,
                        d.undergroundPoints,
                        d.surfacePoints));
            }
        }
        return out;
    }

    public JsonObject toJsonObject(long stayThresholdMs) {
        JsonObject root = new JsonObject();
        root.addProperty("date", date);
        root.addProperty("generatedAt", Instant.now().toString());

        JsonObject playersObj = new JsonObject();
        for (Map.Entry<String, PlayerSnapshot> e : snapshotAll(stayThresholdMs).entrySet()) {
            PlayerSnapshot s = e.getValue();
            JsonObject po = new JsonObject();
            po.addProperty("playtimeMinutes", s.playtimeMs() / 60000);

            JsonObject statsObj = new JsonObject();
            s.stats().forEach(statsObj::addProperty);
            po.add("stats", statsObj);

            po.addProperty("chatCount", s.chatCount());

            JsonArray trailArr = new JsonArray();
            for (TrailPoint tp : s.trail()) {
                JsonObject o = new JsonObject();
                o.addProperty("x", tp.x());
                o.addProperty("y", tp.y());
                o.addProperty("z", tp.z());
                o.addProperty("t", tp.t());
                o.addProperty("world", tp.world());
                trailArr.add(o);
            }
            po.add("trail", trailArr);

            JsonArray breaksArr = new JsonArray();
            for (BreakPoint b : s.breaks()) {
                JsonObject o = new JsonObject();
                o.addProperty("type", b.type());
                o.addProperty("world", b.world());
                o.addProperty("x", b.x());
                o.addProperty("y", b.y());
                o.addProperty("z", b.z());
                o.addProperty("t", b.t());
                breaksArr.add(o);
            }
            po.add("breaks", breaksArr);

            JsonArray staysArr = new JsonArray();
            for (Stay st : s.stays()) {
                JsonObject o = new JsonObject();
                o.addProperty("x", st.x());
                o.addProperty("z", st.z());
                o.addProperty("startT", st.startT());
                o.addProperty("endT", st.endT());
                o.addProperty("minutes", st.minutes());
                staysArr.add(o);
            }
            po.add("stays", staysArr);

            JsonArray eventsArr = new JsonArray();
            for (GameEvent ev : s.events()) {
                JsonObject o = new JsonObject();
                o.addProperty("type", ev.type());
                o.addProperty("world", ev.world());
                o.addProperty("x", ev.x());
                o.addProperty("y", ev.y());
                o.addProperty("z", ev.z());
                o.addProperty("t", ev.t());
                o.addProperty("text", ev.text());
                eventsArr.add(o);
            }
            po.add("events", eventsArr);

            JsonArray chatsArr = new JsonArray();
            for (ChatLine c : s.chats()) {
                JsonObject o = new JsonObject();
                o.addProperty("t", c.t());
                o.addProperty("player", c.player());
                o.addProperty("text", c.text());
                chatsArr.add(o);
            }
            po.add("chats", chatsArr);

            JsonObject ug = new JsonObject();
            ug.addProperty("minY", s.minY());
            ug.addProperty("undergroundPoints", s.undergroundPoints());
            ug.addProperty("surfacePoints", s.surfacePoints());
            po.add("undergroundSummary", ug);

            playersObj.add(e.getKey(), po);
        }
        root.add("players", playersObj);
        return root;
    }

    // 调用方已持有 d 的锁；synchronized 可重入，安全。
    private List<Stay> computeStaysLocked(PlayerData d, long stayThresholdMs) {
        List<Stay> stays = new ArrayList<>();
        List<TrailPoint> trail = d.trail;
        for (int i = 0; i < trail.size() - 1; i++) {
            TrailPoint a = trail.get(i);
            TrailPoint b = trail.get(i + 1);
            long gap = b.t() - a.t();
            // 跳过横跨断点的点对（退出/死亡/传送导致的离线或位移不算停留）
            if (gap >= stayThresholdMs && !hasBreakBetween(d.breaks, a.t(), b.t())) {
                stays.add(new Stay(a.x(), a.z(), a.t(), b.t(), gap / 60000));
            }
        }
        return stays;
    }

    // 断点按时间升序；判断 (aT, bT] 区间内是否存在断点
    private static boolean hasBreakBetween(List<BreakPoint> breaks, long aT, long bT) {
        for (BreakPoint br : breaks) {
            if (br.t() > bT) {
                return false;
            }
            if (br.t() > aT) {
                return true;
            }
        }
        return false;
    }
}
