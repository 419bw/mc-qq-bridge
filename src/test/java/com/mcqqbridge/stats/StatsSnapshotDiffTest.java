package com.mcqqbridge.stats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 本地测试入口（纯 JDK，无 Bukkit 依赖，不打进 jar）：
 * 验证 StatsSnapshotStore 的快照读取 / 相邻 diff / 落盘与读取回环 / 过期清理。
 * 运行：java -cp target/classes:target/test-classes com.mcqqbridge.stats.StatsSnapshotDiffTest
 */
public class StatsSnapshotDiffTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");
    private static final UUID E = UUID.fromString("00000000-0000-0000-0000-00000000000e");

    private static final String V_A1 = """
            {
              "stats": {
                "minecraft:custom": {
                  "minecraft:play_time": 100,
                  "minecraft:walk_one_cm": 5000
                },
                "minecraft:mined": {
                  "minecraft:dirt": 100
                }
              },
              "DataVersion": 3955
            }
            """;

    /** A 的下一窗口：dirt 100->150、walk 5000->8000、新增 leave_game、play_time 100->90（模拟回档递减）。 */
    private static final String V_A2 = """
            {
              "stats": {
                "minecraft:custom": {
                  "minecraft:play_time": 90,
                  "minecraft:walk_one_cm": 8000,
                  "minecraft:leave_game": 2
                },
                "minecraft:mined": {
                  "minecraft:dirt": 150
                }
              },
              "DataVersion": 3955
            }
            """;

    private static final String V_B = """
            {
              "stats": {
                "minecraft:custom": {
                  "minecraft:mob_kills": 3
                }
              }
            }
            """;

    private static final String V_D = """
            {
              "stats": {
                "minecraft:custom": {
                  "minecraft:play_time": 40
                },
                "minecraft:mined": {
                  "minecraft:dirt": 300
                }
              }
            }
            """;

    private static final String V_E = """
            {
              "stats": {
                "minecraft:custom": {
                  "minecraft:play_time": 999
                }
              }
            }
            """;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("mcqq-stats-test");
        try {
            run(tmp);
        } finally {
            try (Stream<Path> s = Files.walk(tmp)) {
                s.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {}
                        });
            }
        }
        System.out.println("StatsSnapshotDiffTest: all PASS");
    }

    private static void run(Path tmp) throws Exception {
        StatsSnapshotStore store = new StatsSnapshotStore(tmp, Logger.getLogger("StatsSnapshotDiffTest"));

        // 1) readCurrent：扫目录所有 <uuid>.json，忽略非 UUID 命名与非 .json 文件
        Path statsDir = tmp.resolve("stats");
        Files.createDirectories(statsDir);
        writeFile(statsDir, A + ".json", V_A1);
        writeFile(statsDir, B + ".json", V_B);
        writeFile(statsDir, E + ".json", V_E);
        writeFile(statsDir, "not-a-uuid.json", V_A1);
        writeFile(statsDir, "readme.txt", "hello");

        Map<UUID, Map<String, Map<String, Integer>>> prev = store.readCurrent(statsDir);
        check(prev.size() == 3, "readCurrent 应只收录 3 个玩家，实际 " + prev.size());
        check(val(prev, A, "minecraft:custom", "minecraft:play_time") == 100, "A 的 play_time 解析错误");
        check(val(prev, A, "minecraft:mined", "minecraft:dirt") == 100, "A 的 mined.dirt 解析错误");

        // 2) diff：正常增量 / 新玩家全额 / 无变化玩家不出现 / 递减项钳除 / 消失玩家不出现
        writeFile(statsDir, A + ".json", V_A2);
        writeFile(statsDir, D + ".json", V_D);
        Files.delete(statsDir.resolve(E + ".json"));
        writeFile(statsDir, "not-a-uuid.json", V_A2); // 非 UUID 文件更新后仍应被忽略

        Map<UUID, Map<String, Map<String, Integer>>> curr = store.readCurrent(statsDir);
        check(curr.size() == 3, "curr 应含 A/B/D，实际 " + curr.size());
        check(!curr.containsKey(E), "E 的文件已删除（模拟覆盖度），不应出现在 curr");

        Map<UUID, Map<String, Integer>> deltas = store.diff(prev, curr);
        check(delta(deltas, A, "minecraft:mined:minecraft:dirt") == 50, "A.dirt 增量应为 50");
        check(delta(deltas, A, "minecraft:custom:minecraft:walk_one_cm") == 3000, "A.walk 增量应为 3000");
        check(delta(deltas, A, "minecraft:custom:minecraft:leave_game") == 2, "A.leave_game 新键应全额计入");
        check(!hasDelta(deltas, A, "minecraft:custom:minecraft:play_time"), "play_time 递减(100->90)应被钳除");
        check(!deltas.containsKey(B), "B 无变化，不应出现在增量中");
        check(delta(deltas, D, "minecraft:mined:minecraft:dirt") == 300, "新玩家 D 应全额计入（无基线按 0）");
        check(!deltas.containsKey(E), "E 不应出现在增量中");

        // 3) 落盘 + loadLatest：按结算时刻命名，取最新一份，重启可恢复
        Map<UUID, Map<String, Map<String, Integer>>> snapshot1 = store.readCurrent(statsDir);
        store.save(snapshot1, LocalDateTime.of(2026, 8, 20, 23, 0, 0));
        Path file2 = store.save(curr, LocalDateTime.of(2026, 8, 21, 23, 0, 0));
        check(Files.isRegularFile(file2), "快照文件应已落盘");
        check(file2.getFileName().toString().equals("2026-08-21_23-00-00.json"),
                "快照文件名应按结算时刻命名，实际 " + file2.getFileName());

        Map<UUID, Map<String, Map<String, Integer>>> latest = store.loadLatest();
        check(latest.size() == 3, "loadLatest 应取最新快照（A/B/D），实际 " + latest.size());
        check(val(latest, A, "minecraft:mined", "minecraft:dirt") == 150, "最新快照 A.dirt 应为 150");
        check(!latest.containsKey(E), "最新快照不应含 E");
        check(latest.containsKey(D), "最新快照应含 D");

        // 4) cleanup：过期快照按保留天数清理，非日期文件不误删，今天的不删
        Path snapDir = tmp.resolve("data").resolve("stats-snapshots");
        writeFile(snapDir, "2026-08-19_23-00-00.json", "{}");
        writeFile(snapDir, "readme.txt", "x");
        int removed = store.cleanup(1);
        check(removed == 1, "应删除 1 个过期快照，实际 " + removed);
        check(Files.exists(file2), "今天的快照不应被清理");
        check(Files.exists(snapDir.resolve("readme.txt")), "非日期命名文件不应被清理");
    }

    private static int val(Map<UUID, Map<String, Map<String, Integer>>> snap, UUID uuid,
                           String cat, String id) {
        Map<String, Integer> m = snap.get(uuid).get(cat);
        check(m != null, uuid + " 应有分类 " + cat);
        Integer v = m.get(id);
        check(v != null, uuid + " 应有 " + cat + ":" + id);
        return v;
    }

    private static int delta(Map<UUID, Map<String, Integer>> deltas, UUID uuid, String key) {
        Map<String, Integer> m = deltas.get(uuid);
        check(m != null, uuid + " 应有增量");
        Integer v = m.get(key);
        check(v != null, uuid + " 应有增量键 " + key);
        return v;
    }

    private static boolean hasDelta(Map<UUID, Map<String, Integer>> deltas, UUID uuid, String key) {
        Map<String, Integer> m = deltas.get(uuid);
        return m != null && m.containsKey(key);
    }

    private static void writeFile(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
        System.out.println("PASS " + msg);
    }
}