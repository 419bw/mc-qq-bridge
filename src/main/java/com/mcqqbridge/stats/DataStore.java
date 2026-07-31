package com.mcqqbridge.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

    public Path save(DailyRecord record) throws IOException {
        Path dir = dataDir();
        Files.createDirectories(dir);
        Path file = dir.resolve(record.getDate() + ".json");
        String json = gson.toJson(record.toJsonObject(stayThresholdMs));
        Files.writeString(file, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return file;
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
