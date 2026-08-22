package com.mcqqbridge.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 原版 stats 文件快照与相邻快照 diff。
 * 每次日报结算把 stats 目录全量读为一份快照落盘，下次结算与最近一份快照 diff 得到窗口增量，
 * 增量写入结算中的 DailyRecord。无内存基线，插件/服务器重启无损。
 * 快照文件名按结算时刻命名（yyyy-MM-dd_HH-mm-ss），同一天内多次结算（手动 report now）天然分文件。
 * 快照读取/落盘做磁盘 IO，调用方须在异步线程调用（与 DataStore 约定一致）。
 */
public class StatsSnapshotStore {

    private static final DateTimeFormatter SNAP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final Path dataFolder;
    private final Logger logger;
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public StatsSnapshotStore(JavaPlugin plugin) {
        this(plugin.getDataFolder().toPath(), plugin.getLogger());
    }

    /** 测试注入：指定数据目录与日志器，纯 JDK 可用。 */
    StatsSnapshotStore(Path dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
    }

    private Path snapDir() {
        return dataFolder.resolve("data").resolve("stats-snapshots");
    }

    /**
     * 读取原版 stats 目录下所有 <uuid>.json（覆盖全部玩家，防"昨天玩今天没玩"的基线缺失），
     * 解析为 玩家 -> 分类（minecraft:custom 等）-> 统计项 -> 数值。目录不存在/为空时返回空 Map。
     */
    public Map<UUID, Map<String, Map<String, Integer>>> readCurrent(Path statsDir) {
        Map<UUID, Map<String, Map<String, Integer>>> snapshot = new HashMap<>();
        if (!Files.isDirectory(statsDir)) {
            return snapshot;
        }
        try (Stream<Path> stream = Files.list(statsDir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".json")) {
                    continue;
                }
                String uuidStr = name.substring(0, name.length() - ".json".length());
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    snapshot.put(uuid, parseVanillaStats(p));
                } catch (IllegalArgumentException ignored) {
                    // 非 UUID 命名的文件不是玩家 stats，跳过
                }
            }
        } catch (IOException e) {
            logger.warning("[Stats] read stats dir failed: " + e.getMessage());
        }
        return snapshot;
    }

    /** 解析单个原版 stats 文件（顶层 stats 包装，key 为 namespace:id）。解析失败返回空 Map。 */
    Map<String, Map<String, Integer>> parseVanillaStats(Path file) {
        Map<String, Map<String, Integer>> parsed = new HashMap<>();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject stats = root.getAsJsonObject("stats");
            if (stats != null) {
                for (Map.Entry<String, JsonElement> cat : stats.entrySet()) {
                    JsonObject catObj = cat.getValue().getAsJsonObject();
                    Map<String, Integer> m = new HashMap<>();
                    for (Map.Entry<String, JsonElement> e : catObj.entrySet()) {
                        m.put(e.getKey(), e.getValue().getAsInt());
                    }
                    parsed.put(cat.getKey(), m);
                }
            }
        } catch (Exception e) {
            logger.warning("[Stats] parse failed for " + file.getFileName() + ": " + e.getMessage());
        }
        return parsed;
    }

    /** 快照落盘（原子写：tmp + move，模式同 DataStore.save）。 */
    public Path save(Map<UUID, Map<String, Map<String, Integer>>> snapshot, LocalDateTime settleTime)
            throws IOException {
        Path dir = snapDir();
        Files.createDirectories(dir);

        JsonObject root = new JsonObject();
        root.addProperty("settledAt", settleTime.format(SNAP_FMT));
        root.addProperty("generatedAt", LocalDateTime.now().toString());
        JsonObject playersObj = new JsonObject();
        for (Map.Entry<UUID, Map<String, Map<String, Integer>>> pe : snapshot.entrySet()) {
            JsonObject catsObj = new JsonObject();
            for (Map.Entry<String, Map<String, Integer>> ce : pe.getValue().entrySet()) {
                JsonObject idsObj = new JsonObject();
                ce.getValue().forEach(idsObj::addProperty);
                catsObj.add(ce.getKey(), idsObj);
            }
            playersObj.add(pe.getKey().toString(), catsObj);
        }
        root.add("players", playersObj);

        String name = settleTime.format(SNAP_FMT) + ".json";
        Path file = dir.resolve(name);
        Path tmp = dir.resolve(name + ".tmp");
        Files.writeString(tmp, gson.toJson(root), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    /** 读取目录中最近一份快照（按文件名排序，时间格式可字典序排列）；无快照返回 null。 */
    public Map<UUID, Map<String, Map<String, Integer>>> loadLatest() {
        Path dir = snapDir();
        if (!Files.isDirectory(dir)) {
            return null;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String name = p.getFileName().toString();
                if (name.endsWith(".json")) {
                    files.add(p);
                }
            }
        } catch (IOException e) {
            logger.warning("[Stats] list snapshots failed: " + e.getMessage());
            return null;
        }
        if (files.isEmpty()) {
            return null;
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return load(files.get(files.size() - 1));
    }

    Map<UUID, Map<String, Map<String, Integer>>> load(Path file) {
        Map<UUID, Map<String, Map<String, Integer>>> snapshot = new HashMap<>();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject playersObj = root.getAsJsonObject("players");
            if (playersObj == null) {
                return snapshot;
            }
            for (Map.Entry<String, JsonElement> pe : playersObj.entrySet()) {
                JsonObject catsObj = pe.getValue().getAsJsonObject();
                Map<String, Map<String, Integer>> cats = new HashMap<>();
                for (Map.Entry<String, JsonElement> ce : catsObj.entrySet()) {
                    JsonObject idsObj = ce.getValue().getAsJsonObject();
                    Map<String, Integer> m = new HashMap<>();
                    for (Map.Entry<String, JsonElement> ie : idsObj.entrySet()) {
                        m.put(ie.getKey(), ie.getValue().getAsInt());
                    }
                    cats.put(ce.getKey(), m);
                }
                snapshot.put(UUID.fromString(pe.getKey()), cats);
            }
        } catch (Exception e) {
            logger.warning("[Stats] snapshot load failed for " + file.getFileName() + ": " + e.getMessage());
        }
        return snapshot;
    }

    /**
     * 相邻快照 diff：curr − prev，flattened key 为 分类:id（如 minecraft:mined:minecraft:dirt）。
     * prev 缺失的玩家按 0 基线（新玩家全额计入窗口）；差值为负（文件回档/重置）的项丢弃。
     */
    public Map<UUID, Map<String, Integer>> diff(Map<UUID, Map<String, Map<String, Integer>>> prev,
                                                Map<UUID, Map<String, Map<String, Integer>>> curr) {
        Map<UUID, Map<String, Integer>> out = new HashMap<>();
        for (Map.Entry<UUID, Map<String, Map<String, Integer>>> pe : curr.entrySet()) {
            UUID uuid = pe.getKey();
            Map<String, Map<String, Integer>> prevCats = prev == null ? null : prev.get(uuid);
            Map<String, Integer> deltas = new HashMap<>();
            for (Map.Entry<String, Map<String, Integer>> ce : pe.getValue().entrySet()) {
                String cat = ce.getKey();
                for (Map.Entry<String, Integer> ie : ce.getValue().entrySet()) {
                    int prevValue = 0;
                    if (prevCats != null && prevCats.containsKey(cat)) {
                        prevValue = prevCats.get(cat).getOrDefault(ie.getKey(), 0);
                    }
                    int d = ie.getValue() - prevValue;
                    if (d > 0) {
                        deltas.put(cat + ":" + ie.getKey(), d);
                    }
                }
            }
            if (!deltas.isEmpty()) {
                out.put(uuid, deltas);
            }
        }
        return out;
    }

    /**
     * 把 diff 增量写入收起窗口的 record（按名字索引）。在线玩家名由主线程收集的 onlineNames 提供，
     * 离线玩家回退查 OfflinePlayer（usercache 本地查询）；查不到名字的玩家跳过并告警。
     */
    public void mergeInto(DailyRecord record, Map<UUID, Map<String, Integer>> deltas,
                          Map<UUID, String> onlineNames) {
        for (Map.Entry<UUID, Map<String, Integer>> e : deltas.entrySet()) {
            String name = onlineNames.get(e.getKey());
            if (name == null) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
                name = op.getName();
            }
            if (name == null) {
                logger.warning("[Stats] cannot resolve name for " + e.getKey() + ", stats skipped");
                continue;
            }
            String resolved = name;
            e.getValue().forEach((k, v) -> record.addStat(resolved, k, v));
        }
    }

    /** 按日报保留天数清理过期快照（文件名前缀 yyyy-MM-dd 参与日期解析）。 */
    public int cleanup(int retentionDays) {
        Path dir = snapDir();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
        int removed = 0;
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".json")) {
                    continue;
                }
                try {
                    LocalDate d = LocalDate.parse(name.substring(0, 10));
                    if (d.isBefore(cutoff) && Files.deleteIfExists(p)) {
                        removed++;
                    }
                } catch (DateTimeParseException ignored) {
                    // 非快照命名文件跳过，不误删
                }
            }
        } catch (IOException e) {
            logger.warning("[Stats] snapshot cleanup failed: " + e.getMessage());
        }
        return removed;
    }
}