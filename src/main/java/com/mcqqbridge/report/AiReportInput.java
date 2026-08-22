package com.mcqqbridge.report;

import com.mcqqbridge.stats.DailyRecord;
import com.mcqqbridge.stats.DailyRecord.BreakPoint;
import com.mcqqbridge.stats.DailyRecord.ChatLine;
import com.mcqqbridge.stats.DailyRecord.GameEvent;
import com.mcqqbridge.stats.DailyRecord.PlayerSnapshot;
import com.mcqqbridge.stats.DailyRecord.Stay;
import com.mcqqbridge.stats.DailyRecord.TrailPoint;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把 snapshotAll() 的玩家快照构建为喂给 LLM 的 JSON 值树（纯 JDK，不依赖 gson/Bukkit，可单测）。
 * 核心：将 trail 点按 30 分钟窗口 X world 聚合为 move 段，再与 breaks（上下线/传送）、
 * events（死亡/成就）、stays（停留）拼成一条按时间升序的时间线，供 LLM 推理
 * "谁在什么时间段做了什么"。时间戳按注入的 {@link ZoneId} 格式化为 HH:mm。
 */
public final class AiReportInput {

    /** 轨迹移动摘要的时间窗口（毫秒）。 */
    private static final long WINDOW_MS = 30L * 60 * 1000;

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    /** 时间线条目：sortT 用于全局排序，data 为最终的 JSON 对象（Map）。 */
    private record Entry(long sortT, Map<String, Object> data) {}

    private AiReportInput() {}

    /**
     * 构建喂 LLM 的 JSON 值树（Map/List/String/Number 嵌套）。
     * zone 决定时间戳如何格式化为 HH:mm：生产传 {@link ZoneId#systemDefault()}，测试传固定 zone。
     */
    public static Map<String, Object> build(Map<String, PlayerSnapshot> snap, String date, ZoneId zone) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("date", date);
        List<Object> players = new ArrayList<>();
        for (Map.Entry<String, PlayerSnapshot> e : snap.entrySet()) {
            players.add(buildPlayer(e.getKey(), e.getValue(), zone));
        }
        root.put("players", players);
        return root;
    }

    private static Map<String, Object> buildPlayer(String name, PlayerSnapshot s, ZoneId zone) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("playtimeMinutes", s.playtimeMs() / 60000);
        p.put("stats", new TreeMap<>(s.stats()));
        p.put("timeline", buildTimeline(s, zone));
        List<Object> chats = new ArrayList<>();
        for (ChatLine c : s.chats()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("time", fmtTime(c.t(), zone));
            line.put("text", c.text());
            chats.add(line);
        }
        p.put("chat", chats);
        return p;
    }

    /** 时间线 = breaks(join/quit/teleport) + events(death/achievement) + stays + move 段，按时间升序。 */
    private static List<Object> buildTimeline(PlayerSnapshot s, ZoneId zone) {
        List<Entry> entries = new ArrayList<>();

        for (BreakPoint b : s.breaks()) {
            String type = switch (b.type()) {
                case "JOIN" -> "join";
                case "QUIT" -> "quit";
                case "TP" -> "teleport";
                default -> null; // DEATH 由 events 覆盖，避免同一次死亡重复出现
            };
            if (type == null) {
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("time", fmtTime(b.t(), zone));
            e.put("type", type);
            e.put("world", b.world());
            e.put("pos", List.of(b.x(), b.y(), b.z()));
            entries.add(new Entry(b.t(), e));
        }

        for (GameEvent ev : s.events()) {
            String type = switch (ev.type()) {
                case "death" -> "death";
                case "advancement" -> "achievement";
                default -> null;
            };
            if (type == null) {
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("time", fmtTime(ev.t(), zone));
            e.put("type", type);
            e.put("world", ev.world());
            e.put("pos", List.of(ev.x(), ev.y(), ev.z()));
            if (!ev.text().isEmpty()) {
                e.put("text", ev.text());
            }
            entries.add(new Entry(ev.t(), e));
        }

        for (Stay st : s.stays()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("time", fmtRange(st.startT(), st.endT(), zone));
            e.put("type", "stay");
            e.put("world", st.world());
            e.put("pos", List.of(st.x(), st.z()));
            e.put("minutes", st.minutes());
            entries.add(new Entry(st.startT(), e));
        }

        entries.addAll(buildMoves(s, zone));

        entries.sort(Comparator.comparingLong(Entry::sortT));
        List<Object> out = new ArrayList<>(entries.size());
        for (Entry en : entries) {
            out.add(en.data());
        }
        return out;
    }

    /** 轨迹按 (时间窗口, world) 分桶聚合为 move 段；空窗口（无点）天然不产出。 */
    private static List<Entry> buildMoves(PlayerSnapshot s, ZoneId zone) {
        Map<Long, Map<String, List<TrailPoint>>> buckets = new LinkedHashMap<>();
        for (TrailPoint tp : s.trail()) {
            long win = Math.floorDiv(tp.t(), WINDOW_MS);
            buckets.computeIfAbsent(win, k -> new LinkedHashMap<>())
                    .computeIfAbsent(tp.world(), w -> new ArrayList<>())
                    .add(tp);
        }
        List<Entry> moves = new ArrayList<>();
        for (Map<String, List<TrailPoint>> byWorld : buckets.values()) {
            for (Map.Entry<String, List<TrailPoint>> we : byWorld.entrySet()) {
                moves.add(summarizeWindow(we.getValue(), we.getKey(), s.breaks(), zone));
            }
        }
        return moves;
    }

    /** 单个 (窗口, world) 桶 -> 一个 move 段。 */
    private static Entry summarizeWindow(List<TrailPoint> pts, String world, List<BreakPoint> breaks, ZoneId zone) {
        pts.sort(Comparator.comparingLong(TrailPoint::t));
        int n = pts.size();

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        long sumX = 0, sumZ = 0;
        int underground = 0;
        for (TrailPoint tp : pts) {
            minX = Math.min(minX, tp.x());
            maxX = Math.max(maxX, tp.x());
            minZ = Math.min(minZ, tp.z());
            maxZ = Math.max(maxZ, tp.z());
            sumX += tp.x();
            sumZ += tp.z();
            if (tp.y() < DailyRecord.UNDERGROUND_Y) {
                underground++;
            }
        }

        int distance = 0;
        for (int i = 0; i < n - 1; i++) {
            TrailPoint a = pts.get(i);
            TrailPoint b = pts.get(i + 1);
            // 跨断点（死亡/传送/重进）的相邻点不算移动，避免瞬移被计入路程
            if (DailyRecord.hasBreakBetween(breaks, a.t(), b.t())) {
                continue;
            }
            int dx = b.x() - a.x();
            int dz = b.z() - a.z();
            distance += (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
        }

        Map<String, Object> e = new LinkedHashMap<>();
        e.put("time", fmtRange(pts.get(0).t(), pts.get(n - 1).t(), zone));
        e.put("type", "move");
        e.put("world", world);
        e.put("center", List.of((int) Math.round((double) sumX / n), (int) Math.round((double) sumZ / n)));
        e.put("spanBlocks", Math.max(maxX - minX, maxZ - minZ));
        e.put("underground", round2(underground / (double) n));
        e.put("distanceBlocks", distance);
        return new Entry(pts.get(0).t(), e);
    }

    private static String fmtTime(long epochMs, ZoneId zone) {
        return Instant.ofEpochMilli(epochMs).atZone(zone).format(HM);
    }

    private static String fmtRange(long start, long end, ZoneId zone) {
        String a = fmtTime(start, zone);
        return start == end ? a : a + "-" + fmtTime(end, zone);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}