package com.mcqqbridge.bridge;

import com.mcqqbridge.config.BridgeConfig;
import com.mcqqbridge.config.BridgeConfig.BridgeMode;
import com.mcqqbridge.qq.QQBotClient;
import com.mcqqbridge.qq.QQGroupMessage;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

public class ChatBridge implements Listener {

    private final JavaPlugin plugin;
    private final BridgeConfig config;
    private final QQBotClient qqClient;
    private final Logger logger;

    public ChatBridge(JavaPlugin plugin, BridgeConfig config, QQBotClient qqClient) {
        this.plugin = plugin;
        this.config = config;
        this.qqClient = qqClient;
        this.logger = plugin.getLogger();
    }

    public void handleQqMessage(QQGroupMessage msg) {
        if (!config.isQqToMc()) return;
        String text = config.getQqFormat()
                .replace("{nickname}", msg.nickname())
                .replace("{message}", msg.content());
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcast(Component.text(text)));
    }

    public void handleGroupOpenIdDetected(String openId) {
        if (config.getGroupOpenId().isEmpty()) {
            config.setAndSaveGroupOpenId(openId);
            logger.info("Auto-detected group_openid: " + openId);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!config.isMcToQq()) return;
        String playerName = event.getPlayer().getName();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        String text = config.getMcFormat()
                .replace("{player}", playerName)
                .replace("{message}", message);
        qqClient.sendGroupMessage(config.getGroupOpenId(), text);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (config.getMode() != BridgeMode.FULL) return;
        String playerName = event.getPlayer().getName();
        qqClient.sendGroupMessage(config.getGroupOpenId(), "[进服] " + playerName + " 加入了游戏");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (config.getMode() != BridgeMode.FULL) return;
        String playerName = event.getPlayer().getName();
        qqClient.sendGroupMessage(config.getGroupOpenId(), "[退服] " + playerName + " 退出了游戏");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (config.getMode() != BridgeMode.FULL) return;
        if (event.deathMessage() == null) return;
        String deathText = PlainTextComponentSerializer.plainText().serialize(event.deathMessage());
        qqClient.sendGroupMessage(config.getGroupOpenId(), "[死亡] " + deathText);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (config.getMode() != BridgeMode.FULL) return;
        var display = event.getAdvancement().getDisplay();
        if (display == null || !display.doesAnnounceToChat()) return;
        String playerName = event.getPlayer().getName();
        String title = PlainTextComponentSerializer.plainText().serialize(display.title());
        qqClient.sendGroupMessage(config.getGroupOpenId(), "[成就] " + playerName + " 获得了成就「" + title + "」");
    }
}
