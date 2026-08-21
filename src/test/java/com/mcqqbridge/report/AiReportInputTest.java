package com.mcqqbridge.report;

import com.mcqqbridge.stats.DailyRecord;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 本地测试入口（纯 JDK，无 Bukkit/gson 依赖，不打进 jar）：
 * 验证 AiReportInput 的窗口聚合 / 断点跳距离 / 跨世界分段 / DEATH 不重复 / stay 与空窗口。
 * 运行：java -cp target/classes:target/test-classes com.mcqqbridge.report.AiReportInputTest
 */
public class AiReportInputTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final long STAY_MS = 30_000L;

    public static void main(String[] args) {
        testMoveAggregation();
        testCrossWorld();
        testDeathNoDuplicate();
        testBreakAtSameTimestamp();
        testStayAndEmptyWindow();
        System.out.println("AiReportInputTest: all PASS");
    }

    // ---- 场景 ----

    /** 窗口聚合数值正确 + 断点严格落在两轨迹点之间时跳距离（不把传送算成移动）。 */
    private static void testMoveAggregation() {
        DailyRecord r = new DailyRecord("2026-08-22");
        String n = "Alice";
        r.addTrail(n, 0, 64, 0, t("2026-08-22T09:05:00Z"), "world");
        r.addTrail(n, 100, 64, 0, t("2026-08-22T09:10:00Z"), "world");
        r.addTrail(n, 100, 64, 200, t("2026-08-22T09:14:00Z"), "world");
        r.addBreak(n, "TP", "world", 100, 64, 200, t("2026-08-22T09:15:00Z"));
        r.addTrail(n, 1000, 64, 3000, t("2026-08-22T09:16:00Z"), "world");
        r.addTrail(n, 1000, 64, 3200, t("2026-08-22T09:20:00Z"), "world");

        Map<String, Object> p = player(buildFor(r), n);
        Map<String, Object> move = findType(timeline(p), "move");
        check(move != null, "应有 move 段");

        check("09:05-09:20".equals(move.get("time")), "move time 应为 09:05-09:20，实际 " + move.get("time"));
        List<Object> center = asArr(move.get("center"));
        check(asInt(center.get(0)) == 440, "center.x 应为 440，实际 " + center.get(0));
        check(asInt(center.get(1)) == 1280, "center.z 应为 1280，实际 " + center.get(1));
        check(asInt(move.get("spanBlocks")) == 3200, "spanBlocks 应为 3200，实际 " + move.get("spanBlocks"));
        check(asDouble(move.get("underground")) == 0.0, "underground 应为 0.0（全部地表）");
        check(asInt(move.get("distanceBlocks")) == 500,
                "distanceBlocks 应为 500（100+200+跳过传送+200），实际 " + move.get("distanceBlocks"));
        check("world".equals(move.get("world")), "world 应为 world");
    }

    /** 同一时间窗口跨世界时拆成两个 move 段（下界坐标尺度不可混算）。 */
    private static void testCrossWorld() {
        DailyRecord r = new DailyRecord("2026-08-22");
        String n = "Bob";
        r.addTrail(n, 0, 64, 0, t("2026-08-22T09:05:00Z"), "world");
        r.addTrail(n, 10, 64, 0, t("2026-08-22T09:10:00Z"), "world");
        r.addTrail(n, 0, 80, 0, t("2026-08-22T09:15:00Z"), "world_nether");
        r.addTrail(n, 5, 80, 0, t("2026-08-22T09:20:00Z"), "world_nether");

        Map<String, Object> p = player(buildFor(r), n);
        List<Object> moves = findAllType(timeline(p), "move");
        check(moves.size() == 2, "跨世界应拆成 2 个 move 段，实际 " + moves.size());

        Map<String, Integer> distByWorld = new java.util.LinkedHashMap<>();
        for (Object o : moves) {
            Map<String, Object> e = asObj(o);
            distByWorld.put(asStr(e.get("world")), asInt(e.get("distanceBlocks")));
        }
        check(distByWorld.containsKey("world") && distByWorld.get("world") == 10,
                "world 段距离应为 10，实际 " + distByWorld.get("world"));
        check(distByWorld.containsKey("world_nether") && distByWorld.get("world_nether") == 5,
                "world_nether 段距离应为 5，实际 " + distByWorld.get("world_nether"));
    }

    /** 同一死亡：break 里的 DEATH 不单独出现，只有 events 里的 death（带 text）出现一次。 */
    private static void testDeathNoDuplicate() {
        DailyRecord r = new DailyRecord("2026-08-22");
        String n = "Carol";
        r.addBreak(n, "JOIN", "world", 0, 64, 0, t("2026-08-22T09:00:00Z"));
        r.addEvent(n, "death", "world", 10, 20, 30, t("2026-08-22T10:00:00Z"), "被骷髅射死");
        r.addBreak(n, "DEATH", "world", 10, 20, 30, t("2026-08-22T10:00:00Z"));

        Map<String, Object> p = player(buildFor(r), n);
        List<Object> tl = timeline(p);
        int join = 0, death = 0;
        for (Object o : tl) {
            Map<String, Object> e = asObj(o);
            if ("join".equals(e.get("type"))) {
                join++;
            } else if ("death".equals(e.get("type"))) {
                death++;
                check("被骷髅射死".equals(e.get("text")), "death 应带 text，实际 " + e.get("text"));
            }
        }
        check(join == 1, "join 应出现 1 次，实际 " + join);
        check(death == 1, "death 应出现 1 次（break DEATH 不重复），实际 " + death);
    }

    /** 断点与轨迹点同 t（死亡/传送真实时序）：瞬移不计入移动距离，走向死亡点的真实移动保留。 */
    private static void testBreakAtSameTimestamp() {
        DailyRecord r = new DailyRecord("2026-08-22");
        String n = "Erin";
        r.addTrail(n, 0, 64, 0, t("2026-08-22T09:10:00Z"), "world");   // 走向死亡点
        r.addTrail(n, 100, 64, 0, t("2026-08-22T09:15:00Z"), "world");  // 死亡点，与 DEATH 断点同 t
        r.addBreak(n, "DEATH", "world", 100, 64, 0, t("2026-08-22T09:15:00Z"));
        r.addTrail(n, 5000, 64, 0, t("2026-08-22T09:16:00Z"), "world"); // 复活点（瞬移 4900 格）

        Map<String, Object> p = player(buildFor(r), n);
        Map<String, Object> move = findType(timeline(p), "move");
        check(move != null, "应有 move 段");
        check(asInt(move.get("distanceBlocks")) == 100,
                "断点与点同 t 的瞬移应跳过而保留真实移动，distanceBlocks 应为 100，实际 "
                        + move.get("distanceBlocks"));
    }

    /** 停留由 gap >= stayThreshold 的相邻点生成；无点窗口不产出 move 段。 */
    private static void testStayAndEmptyWindow() {
        DailyRecord r = new DailyRecord("2026-08-22");
        String n = "Dave";
        r.addTrail(n, 10, 64, 10, t("2026-08-22T09:00:00Z"), "world");
        r.addTrail(n, 10, 64, 10, t("2026-08-22T09:20:00Z"), "world");

        Map<String, Object> p = player(buildFor(r), n);
        List<Object> tl = timeline(p);

        Map<String, Object> stay = findType(tl, "stay");
        check(stay != null, "应有 stay 段");
        check(asInt(stay.get("minutes")) == 20, "stay minutes 应为 20，实际 " + stay.get("minutes"));
        List<Object> pos = asArr(stay.get("pos"));
        check(asInt(pos.get(0)) == 10 && asInt(pos.get(1)) == 10, "stay pos 应为 [10,10]");

        List<Object> moves = findAllType(tl, "move");
        check(moves.size() == 1, "仅有 09:00-09:20 一个有点的窗口，move 段应为 1，实际 " + moves.size());
    }

    // ---- 辅助 ----

    private static long t(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    private static Map<String, Object> buildFor(DailyRecord r) {
        return AiReportInput.build(r.snapshotAll(STAY_MS), r.getDate(), UTC);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObj(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asArr(Object o) {
        return (List<Object>) o;
    }

    private static int asInt(Object o) {
        return ((Number) o).intValue();
    }

    private static double asDouble(Object o) {
        return ((Number) o).doubleValue();
    }

    private static String asStr(Object o) {
        return (String) o;
    }

    private static Map<String, Object> player(Map<String, Object> root, String name) {
        for (Object o : asArr(root.get("players"))) {
            Map<String, Object> p = asObj(o);
            if (name.equals(asStr(p.get("name")))) {
                return p;
            }
        }
        throw new AssertionError("player not found: " + name);
    }

    private static List<Object> timeline(Map<String, Object> p) {
        return asArr(p.get("timeline"));
    }

    private static Map<String, Object> findType(List<Object> tl, String type) {
        for (Object o : tl) {
            Map<String, Object> e = asObj(o);
            if (type.equals(e.get("type"))) {
                return e;
            }
        }
        return null;
    }

    private static List<Object> findAllType(List<Object> tl, String type) {
        List<Object> out = new ArrayList<>();
        for (Object o : tl) {
            Map<String, Object> e = asObj(o);
            if (type.equals(e.get("type"))) {
                out.add(e);
            }
        }
        return out;
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
        System.out.println("PASS " + msg);
    }
}