package com.mcqqbridge.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicReference;

public class BridgeConfig {

    public enum BridgeMode {
        CHAT, FULL
    }

    private final JavaPlugin plugin;

    private boolean mcToQq;
    private boolean qqToMc;
    private String mcFormat;
    private String qqFormat;
    private BridgeMode mode;
    private String appId;
    private String appSecret;
    private final AtomicReference<String> groupOpenId = new AtomicReference<>("");

    private boolean reportEnabled;
    private int reportHour;
    private int reportMinute;
    private int retentionDays;
    private int mapMaxWidth;
    private int mapPadding;
    private long trailIntervalMs;
    private long stayThresholdMs;

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
}
