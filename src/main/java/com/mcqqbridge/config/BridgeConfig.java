package com.mcqqbridge.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicReference;

public class BridgeConfig {

    public enum BridgeMode {
        CHAT, FULL
    }

    private final JavaPlugin plugin;

    private volatile boolean mcToQq;
    private volatile boolean qqToMc;
    private volatile String mcFormat;
    private volatile String qqFormat;
    private volatile BridgeMode mode;
    private String appId;
    private String appSecret;
    private final AtomicReference<String> groupOpenId = new AtomicReference<>("");

    private volatile boolean reportEnabled;
    private int reportHour;
    private int reportMinute;
    private int retentionDays;
    private int mapMaxWidth;
    private int mapPadding;
    private long trailIntervalMs;
    private long stayThresholdMs;
    private boolean terrainEnabled;
    private boolean aiReportEnabled;
    private String aiBaseUrl;
    private String aiApiKey;
    private String aiModel;
    private int aiTimeoutSec;

    public BridgeConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        FileConfiguration c = plugin.getConfig();
        mcToQq = c.getBoolean("bridge.mc-to-qq", true);
        qqToMc = c.getBoolean("bridge.qq-to-mc", true);
        mcFormat = c.getString("bridge.mc-format", "[MC] <{player}> {message}");
        qqFormat = c.getString("bridge.qq-format", "[QQ] <{nickname}> {message}");
        appId = c.getString("qq.app-id", "");
        appSecret = c.getString("qq.app-secret", "");
        String savedGroupId = c.getString("qq.group-openid", "");
        groupOpenId.set(savedGroupId == null ? "" : savedGroupId);
        String modeStr = c.getString("bridge.mode", "chat");
        try {
            mode = BridgeMode.valueOf(modeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            mode = BridgeMode.CHAT;
        }

        reportEnabled = c.getBoolean("report.enabled", true);
        parseReportTime(c.getString("report.time", "23:00"));
        retentionDays = c.getInt("report.retention-days", 30);
        mapMaxWidth = c.getInt("report.map.max-width", 1024);
        mapPadding = c.getInt("report.map.padding", 64);
        trailIntervalMs = c.getInt("report.trail.time-threshold-sec", 5) * 1000L;
        stayThresholdMs = c.getInt("report.trail.stay-threshold-sec", 30) * 1000L;
        terrainEnabled = c.getBoolean("report.terrain.enabled", true);
        aiReportEnabled = c.getBoolean("report.ai.enabled", true);
        aiBaseUrl = c.getString("report.ai.base-url", "https://api.deepseek.com");
        aiApiKey = c.getString("report.ai.api-key", "");
        aiModel = c.getString("report.ai.model", "deepseek-chat");
        aiTimeoutSec = c.getInt("report.ai.timeout-sec", 30);
    }

    private void parseReportTime(String time) {
        int h = 23;
        int m = 0;
        if (time != null) {
            String[] parts = time.split(":");
            try {
                if (parts.length >= 1) h = Integer.parseInt(parts[0].trim());
                if (parts.length >= 2) m = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        this.reportHour = (h < 0 || h > 23) ? 23 : h;
        this.reportMinute = (m < 0 || m > 59) ? 0 : m;
    }

    public boolean isMcToQq() {
        return mcToQq;
    }

    public boolean isQqToMc() {
        return qqToMc;
    }

    public String getMcFormat() {
        return mcFormat;
    }

    public String getQqFormat() {
        return qqFormat;
    }

    public BridgeMode getMode() {
        return mode;
    }

    public String getAppId() {
        return appId == null ? "" : appId;
    }

    public String getAppSecret() {
        return appSecret == null ? "" : appSecret;
    }

    public String getGroupOpenId() {
        return groupOpenId.get();
    }

    public boolean isReportEnabled() {
        return reportEnabled;
    }

    public int getReportHour() {
        return reportHour;
    }

    public int getReportMinute() {
        return reportMinute;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public int getMapMaxWidth() {
        return mapMaxWidth;
    }

    public int getMapPadding() {
        return mapPadding;
    }

    public long getTrailIntervalMs() {
        return trailIntervalMs;
    }

    public long getStayThresholdMs() {
        return stayThresholdMs;
    }

    public boolean isTerrainEnabled() {
        return terrainEnabled;
    }

    public boolean isAiReportEnabled() {
        return aiReportEnabled;
    }

    public String getAiBaseUrl() {
        return aiBaseUrl == null ? "" : aiBaseUrl;
    }

    public String getAiApiKey() {
        return aiApiKey == null ? "" : aiApiKey;
    }

    public String getAiModel() {
        return aiModel == null ? "" : aiModel;
    }

    public int getAiTimeoutSec() {
        return aiTimeoutSec;
    }

    public void setMode(BridgeMode newMode) {
        this.mode = newMode;
        plugin.getConfig().set("bridge.mode", newMode.name().toLowerCase());
        plugin.saveConfig();
    }

    public void setReportEnabled(boolean enabled) {
        this.reportEnabled = enabled;
        plugin.getConfig().set("report.enabled", enabled);
        plugin.saveConfig();
    }

    public void setAndSaveGroupOpenId(String openId) {
        groupOpenId.set(openId);
        plugin.getConfig().set("qq.group-openid", openId);
        plugin.saveConfig();
    }

    public void setMcToQq(boolean enabled) {
        this.mcToQq = enabled;
        plugin.getConfig().set("bridge.mc-to-qq", enabled);
        plugin.saveConfig();
    }

    public void setQqToMc(boolean enabled) {
        this.qqToMc = enabled;
        plugin.getConfig().set("bridge.qq-to-mc", enabled);
        plugin.saveConfig();
    }

    public void setMcFormat(String format) {
        this.mcFormat = format;
        plugin.getConfig().set("bridge.mc-format", format);
        plugin.saveConfig();
    }

    public void setQqFormat(String format) {
        this.qqFormat = format;
        plugin.getConfig().set("bridge.qq-format", format);
        plugin.saveConfig();
    }

    public void setReportTime(String time) {
        parseReportTime(time);
        plugin.getConfig().set("report.time", time);
        plugin.saveConfig();
    }

    public void setRetentionDays(int days) {
        this.retentionDays = days;
        plugin.getConfig().set("report.retention-days", days);
        plugin.saveConfig();
    }

    public void setMapMaxWidth(int width) {
        this.mapMaxWidth = width;
        plugin.getConfig().set("report.map.max-width", width);
        plugin.saveConfig();
    }

    public void setMapPadding(int padding) {
        this.mapPadding = padding;
        plugin.getConfig().set("report.map.padding", padding);
        plugin.saveConfig();
    }

    public void setTrailIntervalSec(int sec) {
        this.trailIntervalMs = sec * 1000L;
        plugin.getConfig().set("report.trail.time-threshold-sec", sec);
        plugin.saveConfig();
    }

    public void setStayThresholdSec(int sec) {
        this.stayThresholdMs = sec * 1000L;
        plugin.getConfig().set("report.trail.stay-threshold-sec", sec);
        plugin.saveConfig();
    }

    public void setTerrainEnabled(boolean enabled) {
        this.terrainEnabled = enabled;
        plugin.getConfig().set("report.terrain.enabled", enabled);
        plugin.saveConfig();
    }

    public void setAiReportEnabled(boolean enabled) {
        this.aiReportEnabled = enabled;
        plugin.getConfig().set("report.ai.enabled", enabled);
        plugin.saveConfig();
    }

    public void setAiBaseUrl(String url) {
        this.aiBaseUrl = url;
        plugin.getConfig().set("report.ai.base-url", url);
        plugin.saveConfig();
    }

    public void setAiApiKey(String key) {
        this.aiApiKey = key;
        plugin.getConfig().set("report.ai.api-key", key);
        plugin.saveConfig();
    }

    public void setAiModel(String model) {
        this.aiModel = model;
        plugin.getConfig().set("report.ai.model", model);
        plugin.saveConfig();
    }

    public void setAiTimeoutSec(int sec) {
        this.aiTimeoutSec = sec;
        plugin.getConfig().set("report.ai.timeout-sec", sec);
        plugin.saveConfig();
    }

    public void setAppId(String appId) {
        this.appId = appId;
        plugin.getConfig().set("qq.app-id", appId);
        plugin.saveConfig();
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
        plugin.getConfig().set("qq.app-secret", appSecret);
        plugin.saveConfig();
    }
}
