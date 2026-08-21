package com.mcqqbridge.report;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcqqbridge.stats.DailyRecord;
import com.mcqqbridge.stats.DailyRecord.PlayerSnapshot;
import com.mcqqbridge.stats.DailyRecordSerializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 本地集成测试：读取真实日报 JSON -> 使用生产 AiReportSummarizer 调用 DeepSeek API。
 * 运行：java -cp <classpath> com.mcqqbridge.report.AiApiLiveTest <jsonFile> <apiKey>
 */
public class AiApiLiveTest {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: AiApiLiveTest <dailyRecord.json> <apiKey>");
            System.exit(1);
        }
        Path jsonPath = Path.of(args[0]);
        String apiKey = args[1];
        String model = args.length >= 3 ? args[2] : "deepseek-v4-flash";

        // 1. 反序列化日报 JSON
        String jsonText = Files.readString(jsonPath);
        JsonObject root = JsonParser.parseString(jsonText).getAsJsonObject();
        DailyRecord record = DailyRecordSerializer.fromJson(root);
        String date = record.getDate();
        System.out.println("=== 日报数据加载完成: " + date + " ===");

        Map<String, PlayerSnapshot> snap = record.snapshotAll(30_000L);
        for (Map.Entry<String, PlayerSnapshot> e : snap.entrySet()) {
            PlayerSnapshot s = e.getValue();
            System.out.printf("  玩家 %s: 在线 %d 分钟, 轨迹 %d 点, 断点 %d, 事件 %d, 聊天 %d%n",
                    e.getKey(), s.playtimeMs() / 60000,
                    s.trail().size(), s.breaks().size(), s.events().size(), s.chats().size());
        }

        // 2. 使用生产 AiReportSummarizer
        System.out.println("\n=== 使用生产 AiReportSummarizer (model=" + model + ") ===");
        Logger logger = Logger.getLogger("AiApiLiveTest");
        AiReportSummarizer summarizer = new AiReportSummarizer(
                "https://api.deepseek.com", apiKey, model, 60, logger);

        long t0 = System.currentTimeMillis();
        String result = summarizer.summarize(snap, date);
        long elapsed = System.currentTimeMillis() - t0;

        System.out.println("耗时: " + elapsed + "ms");
        if (result == null) {
            System.out.println("AI 返回 null（失败）");
        } else {
            System.out.println("\n--- AI 日报总结 ---\n" + result);
        }
    }
}
