package com.mcqqbridge.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 每日记录落盘与过期清理。save/cleanup 做磁盘 IO，调用方须在异步线程调用。
 */
public class DataStore {

    private final JavaPlugin plugin;
    private final long stayThresholdMs;
    private final Logger logger;
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public DataStore(JavaPlugin plugin, long stayThresholdMs) {
        this.plugin = plugin;
        this.stayThresholdMs = stayThresholdMs;
        this.logger = plugin.getLogger();
    }

    private Path dataDir() {
        return plugin.getDataFolder().toPath().resolve("data");
    }

    /**
     * 落盘（原子写）：先写临时文件再替换，避免崩溃留半文件；覆盖前旧文件保留为 .bak 上一版兜底。
     * 调用方须在异步线程调用。
     */
    public Path save(DailyRecord record) throws IOException {
        Path dir = dataDir();
        Files.createDirectories(dir);
        String date = record.getDate();
        Path file = dir.resolve(date + ".json");
        String json = gson.toJson(DailyRecordSerializer.toJsonObject(record, stayThresholdMs));
        Path tmp = dir.resolve(date + ".json.tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Path bak = dir.resolve(date + ".json.bak");
        if (Files.exists(file)) {
            Files.move(file, bak, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    /** 读取指定日期的记录（不存在/解析失败返回 null）。插件启动恢复当天数据用。 */
    public DailyRecord load(String date) {
        Path file = dataDir().resolve(date + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            return DailyRecordSerializer.fromJson(root);
        } catch (Exception e) {
            logger.warning("[DataStore] load failed for " + date + ": " + e.getMessage());
            return null;
        }
    }

    public int cleanup(int retentionDays) {
        Path dir = dataDir();
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
                String dateStr = name.substring(0, name.length() - ".json".length());
                try {
                    LocalDate d = LocalDate.parse(dateStr);
                    if (d.isBefore(cutoff) && Files.deleteIfExists(p)) {
                        removed++;
                    }
                } catch (DateTimeParseException ignored) {
                    // 非日期命名的文件跳过，不误删
                }
            }
        } catch (IOException e) {
            logger.warning("[DataStore] cleanup failed: " + e.getMessage());
        }
        return removed;
    }
}
