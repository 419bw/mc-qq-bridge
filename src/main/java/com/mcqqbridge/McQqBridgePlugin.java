package com.mcqqbridge;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicReference;

public class McQqBridgePlugin extends JavaPlugin implements Listener {

    private QQBotClient qqClient;
    private final AtomicReference<String> groupOpenId = new AtomicReference<>("");
    private boolean mcToQq;
    private boolean qqToMc;
    private String mcFormat;
    private String qqFormat;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        String appId = getConfig().getString("qq.app-id", "");
        String appSecret = getConfig().getString("qq.app-secret", "");

        if (appId.isEmpty() || appSecret.isEmpty()) {
            getLogger().warning("QQ AppID or AppSecret not configured! Plugin disabled.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        String savedGroupId = getConfig().getString("qq.group-openid", "");
        if (!savedGroupId.isEmpty()) {
            groupOpenId.set(savedGroupId);
        }

        qqClient = new QQBotClient(appId, appSecret, getLogger());

        qqClient.setOnGroupMessage(msg -> {
            if (!qqToMc) return;
            String text = qqFormat
                    .replace("{nickname}", msg.nickname())
                    .replace("{message}", msg.content());
            Bukkit.getScheduler().runTask(this, () -> {
                Bukkit.broadcast(net.kyori.adventure.text.Component.text(text));
            });
        });

        qqClient.setOnGroupOpenIdDetected(openId -> {
            if (groupOpenId.get().isEmpty()) {
                groupOpenId.set(openId);
                getLogger().info("Auto-detected group_openid: " + openId);
                getConfig().set("qq.group-openid", openId);
                saveConfig();
            }
        });

        Bukkit.getPluginManager().registerEvents(this, this);
        qqClient.start();
        getLogger().info("McQqBridge enabled! Waiting for QQ connection...");
    }

    @Override
    public void onDisable() {
        if (qqClient != null) {
            qqClient.stop();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!mcToQq) return;
        String playerName = event.getPlayer().getName();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        String text = mcFormat
                .replace("{player}", playerName)
                .replace("{message}", message);
        qqClient.sendGroupMessage(groupOpenId.get(), text);
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        mcToQq = config.getBoolean("bridge.mc-to-qq", true);
        qqToMc = config.getBoolean("bridge.qq-to-mc", true);
        mcFormat = config.getString("bridge.mc-format", "[MC] <{player}> {message}");
        qqFormat = config.getString("bridge.qq-format", "[QQ] <{nickname}> {message}");
    }
}
