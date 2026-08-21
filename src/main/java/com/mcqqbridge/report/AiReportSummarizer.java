package com.mcqqbridge.report;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcqqbridge.stats.DailyRecord.PlayerSnapshot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 日报文字的 AI 生成门面：把 {@link AiReportInput} 构建的数据喂给 DeepSeek（OpenAI 兼容端点），
 * 取回中文总结。不依赖 BridgeConfig/Bukkit，可在异步线程调用。
 * 任何一步失败（网络、鉴权、解析、超时）都返回 null，由调用方降级到固定日报文本。
 */
public class AiReportSummarizer {

    private static final String SYSTEM_PROMPT = """
            你是一个 Minecraft 服务器的日报总结助手。下面会给你一份 JSON，包含当日各玩家的统计数据、
            行为时间线和聊天记录。请根据这些数据生成一段简短、口语化的中文日报总结。

            数据字段说明：
            - stats 是当日全量统计增量。单位：minecraft:custom:minecraft:play_time 是游戏 tick（20 tick = 1 秒）；
              以 _one_cm 结尾的是距离，单位厘米；mined/crafted/picked_up/killed 等是次数。
            - timeline 按时间升序排列，type 含义：
              * join / quit：上线 / 下线；teleport：传送（pos 是传送前位置）。
              * death：死亡，text 是死亡原因；achievement：获得成就，text 是成就名。
              * stay：长时间停留，minutes 是停留分钟数，pos 是停留坐标。
              * move：一段时间内的移动摘要——center 是活动中心坐标，spanBlocks 是活动范围宽度（格），
                underground 是地下活动占比（0~1，越高越在矿洞/地下），distanceBlocks 是该段累计移动距离（格）。
            - chat 是玩家游戏内发言，是与行为时间线对照的直接依据。

            输出要求：结合 timeline（谁在什么时间段做了什么）、stats（挖掘/建造/击杀总量）与 chat，
            概括当日情况，重点是"谁在什么时间段做了什么事"，以及挖掘、建造、击杀、探索等亮点和有趣事件。
            直接输出总结正文，不要解释分析过程，不要复述原始数据。
            """;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutSec;
    private final Logger logger;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public AiReportSummarizer(String baseUrl, String apiKey, String model, int timeoutSec, Logger logger) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSec = timeoutSec;
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSec))
                .build();
    }

    /** 生成 AI 总结；失败返回 null。可重复调用，无内部状态。 */
    public String summarize(Map<String, PlayerSnapshot> snap, String date) {
        try {
            String dataJson = gson.toJson(AiReportInput.build(snap, date, ZoneId.systemDefault()));

            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", SYSTEM_PROMPT);
            JsonObject usr = new JsonObject();
            usr.addProperty("role", "user");
            usr.addProperty("content", dataJson);
            JsonArray messages = new JsonArray();
            messages.add(sys);
            messages.add(usr);

            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.add("messages", messages);
            body.addProperty("temperature", 0.7);
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "enabled");
            body.add("thinking", thinking);
            body.addProperty("reasoning_effort", "low");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                logger.warning("[AI] summarize HTTP " + response.statusCode() + ": " + response.body());
                return null;
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            return json.getAsJsonArray("choices").get(0)
                    .getAsJsonObject().getAsJsonObject("message")
                    .get("content").getAsString();
        } catch (Exception e) {
            logger.warning("[AI] summarize failed: " + e.getMessage());
            return null;
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}