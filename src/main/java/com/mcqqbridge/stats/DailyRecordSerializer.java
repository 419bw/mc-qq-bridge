package com.mcqqbridge.stats;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mcqqbridge.stats.DailyRecord.BreakPoint;
import com.mcqqbridge.stats.DailyRecord.ChatLine;
import com.mcqqbridge.stats.DailyRecord.GameEvent;
import com.mcqqbridge.stats.DailyRecord.PlayerSnapshot;
import com.mcqqbridge.stats.DailyRecord.Stay;
import com.mcqqbridge.stats.DailyRecord.TrailPoint;

import java.time.Instant;
import java.util.Map;

/**
 * DailyRecord 的 JSON 序列化/反序列化。从 DailyRecord 提取，使模型类不承担序列化职责。
 * JSON 格式与原版完全一致，保证数据文件向后兼容。
 */
public final class DailyRecordSerializer {

    private DailyRecordSerializer() {}

    public static JsonObject toJsonObject(DailyRecord record, long stayThresholdMs) {
        JsonObject root = new JsonObject();
        root.addProperty("date", record.getDate());
        root.addProperty("generatedAt", Instant.now().toString());

        JsonObject playersObj = new JsonObject();
        for (Map.Entry<String, PlayerSnapshot> e : record.snapshotAll(stayThresholdMs).entrySet()) {
            PlayerSnapshot s = e.getValue();
            JsonObject po = new JsonObject();
            po.addProperty("playtimeMinutes", s.playtimeMs() / 60000);

            JsonObject statsObj = new JsonObject();
            s.stats().forEach(statsObj::addProperty);
            po.add("stats", statsObj);

            po.addProperty("chatCount", s.chatCount());
            po.add("trail", trailToJson(s));
            po.add("breaks", breaksToJson(s));
            po.add("stays", staysToJson(s));
            po.add("events", eventsToJson(s));
            po.add("chats", chatsToJson(s));

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

    /**
     * 从 JSON 反序列化（DataStore.load 用）。stays 不还原（snapshotAll 时从 trail 重算），
     * minY/地下点数由 addTrail 自动重建。字段缺失按空处理（兼容历史版本）。
     */
    public static DailyRecord fromJson(JsonObject root) {
        DailyRecord record = new DailyRecord(root.get("date").getAsString());
        JsonObject playersObj = root.getAsJsonObject("players");
        if (playersObj == null) {
            return record;
        }
        for (Map.Entry<String, JsonElement> e : playersObj.entrySet()) {
            String name = e.getKey();
            JsonObject po = e.getValue().getAsJsonObject();

            if (po.has("playtimeMinutes")) {
                record.addPlaytime(name, po.get("playtimeMinutes").getAsLong() * 60000);
            }
            JsonObject stats = po.getAsJsonObject("stats");
            if (stats != null) {
                for (Map.Entry<String, JsonElement> se : stats.entrySet()) {
                    record.addStat(name, se.getKey(), se.getValue().getAsInt());
                }
            }
            JsonArray chats = po.getAsJsonArray("chats");
            if (chats != null) {
                for (JsonElement ce : chats) {
                    JsonObject co = ce.getAsJsonObject();
                    record.addChat(name, co.get("t").getAsLong(), co.get("text").getAsString());
                }
            }
            JsonArray trail = po.getAsJsonArray("trail");
            if (trail != null) {
                for (JsonElement te : trail) {
                    JsonObject to = te.getAsJsonObject();
                    record.addTrail(name, to.get("x").getAsInt(), to.get("y").getAsInt(), to.get("z").getAsInt(),
                            to.get("t").getAsLong(), to.get("world").getAsString());
                }
            }
            JsonArray breaks = po.getAsJsonArray("breaks");
            if (breaks != null) {
                for (JsonElement be : breaks) {
                    JsonObject bo = be.getAsJsonObject();
                    record.addBreak(name, bo.get("type").getAsString(), bo.get("world").getAsString(),
                            bo.get("x").getAsInt(), bo.get("y").getAsInt(), bo.get("z").getAsInt(),
                            bo.get("t").getAsLong());
                }
            }
            JsonArray events = po.getAsJsonArray("events");
            if (events != null) {
                for (JsonElement ee : events) {
                    JsonObject eo = ee.getAsJsonObject();
                    record.addEvent(name, eo.get("type").getAsString(), eo.get("world").getAsString(),
                            eo.get("x").getAsInt(), eo.get("y").getAsInt(), eo.get("z").getAsInt(),
                            eo.get("t").getAsLong(), eo.has("text") ? eo.get("text").getAsString() : "");
                }
            }
        }
        return record;
    }

    // ---- 各 record 类型的序列化辅助 ----

    private static JsonArray trailToJson(PlayerSnapshot s) {
        JsonArray arr = new JsonArray();
        for (TrailPoint tp : s.trail()) {
            JsonObject o = new JsonObject();
            o.addProperty("x", tp.x());
            o.addProperty("y", tp.y());
            o.addProperty("z", tp.z());
            o.addProperty("t", tp.t());
            o.addProperty("world", tp.world());
            arr.add(o);
        }
        return arr;
    }

    private static JsonArray breaksToJson(PlayerSnapshot s) {
        JsonArray arr = new JsonArray();
        for (BreakPoint b : s.breaks()) {
            JsonObject o = new JsonObject();
            o.addProperty("type", b.type());
            o.addProperty("world", b.world());
            o.addProperty("x", b.x());
            o.addProperty("y", b.y());
            o.addProperty("z", b.z());
            o.addProperty("t", b.t());
            arr.add(o);
        }
        return arr;
    }

    private static JsonArray staysToJson(PlayerSnapshot s) {
        JsonArray arr = new JsonArray();
        for (Stay st : s.stays()) {
            JsonObject o = new JsonObject();
            o.addProperty("x", st.x());
            o.addProperty("z", st.z());
            o.addProperty("startT", st.startT());
            o.addProperty("endT", st.endT());
            o.addProperty("minutes", st.minutes());
            o.addProperty("world", st.world());
            arr.add(o);
        }
        return arr;
    }

    private static JsonArray eventsToJson(PlayerSnapshot s) {
        JsonArray arr = new JsonArray();
        for (GameEvent ev : s.events()) {
            JsonObject o = new JsonObject();
            o.addProperty("type", ev.type());
            o.addProperty("world", ev.world());
            o.addProperty("x", ev.x());
            o.addProperty("y", ev.y());
            o.addProperty("z", ev.z());
            o.addProperty("t", ev.t());
            o.addProperty("text", ev.text());
            arr.add(o);
        }
        return arr;
    }

    private static JsonArray chatsToJson(PlayerSnapshot s) {
        JsonArray arr = new JsonArray();
        for (ChatLine c : s.chats()) {
            JsonObject o = new JsonObject();
            o.addProperty("t", c.t());
            o.addProperty("player", c.player());
            o.addProperty("text", c.text());
            arr.add(o);
        }
        return arr;
    }
}
