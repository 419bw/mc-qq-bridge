package com.mcqqbridge;

import com.mcqqbridge.bridge.ChatBridge;
import com.mcqqbridge.command.McQqCommand;
import com.mcqqbridge.config.BridgeConfig;
import com.mcqqbridge.qq.QQBotClient;
import com.mcqqbridge.report.DailyReportScheduler;
import com.mcqqbridge.report.MapRenderer;
import com.mcqqbridge.report.ReportFormatter;
import com.mcqqbridge.stats.DataStore;
import com.mcqqbridge.stats.PlayerTracker;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class McQqBridgePlugin extends JavaPlugin {

    private BridgeConfig config;
    private QQBotClient qqClient;
    private PlayerTracker tracker;
    private DailyReportScheduler reportScheduler;

    @Override
    public void onEnable() {
        config = new BridgeConfig(this);
        config.load();

        if (config.getAppId().isEmpty() || config.getAppSecret().isEmpty()) {
            getLogger().warning("QQ AppID or AppSecret not configured! Plugin disabled.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        qqClient = new QQBotClient(config.getAppId(), config.getAppSecret(), getLogger());

        ChatBridge chatBridge = new ChatBridge(this, config, qqClient);
        qqClient.setOnGroupMessage(chatBridge::handleQqMessage);
        qqClient.setOnGroupOpenIdDetected(chatBridge::handleGroupOpenIdDetected);
        Bukkit.getPluginManager().registerEvents(chatBridge, this);

        tracker = new PlayerTracker(this, config.getTrailIntervalMs(), config.getStayThresholdMs());
        Bukkit.getPluginManager().registerEvents(tracker, this);

        DataStore dataStore = new DataStore(this, config.getStayThresholdMs());
        MapRenderer renderer = new MapRenderer(this, config.getMapMaxWidth(), config.getMapPadding());
        ReportFormatter formatter = new ReportFormatter();
        reportScheduler = new DailyReportScheduler(this, config, tracker, dataStore, renderer,
                formatter, qqClient, config.getReportHour(), config.getReportMinute(),
                config.getRetentionDays(), config.isReportEnabled());
        reportScheduler.start();

        McQqCommand command = new McQqCommand(this, config, reportScheduler);
        getCommand("mcqq").setExecutor(command);

        qqClient.start();
        getLogger().info("McQqBridge enabled! Mode: " + config.getMode());
    }

    @Override
    public void onDisable() {
        if (reportScheduler != null) {
            reportScheduler.stop();
        }
        if (qqClient != null) {
            qqClient.stop();
        }
    }
}
