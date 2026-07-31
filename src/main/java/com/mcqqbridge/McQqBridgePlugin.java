package com.mcqqbridge;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicReference;

public class McQqBridgePlugin extends JavaPlugin implements Listener {

    public enum BridgeMode {
        CHAT, FULL
    }

    private QQBotClient qqClient;
    private final AtomicReference<String> groupOpenId = new AtomicReference<>("");
    private boolean mcToQq;
    private boolean qqToMc;
    private String mcFormat;
    private String qqFormat;
    private BridgeMode mode;

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
        getLogger().info("McQqBridge enabled! Mode: " + mode);
    }

    @Override
    public void onDisable() {
        if (qqClient != null) {
            qqClient.stop();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "mode" -> {
                if (args.length < 2) {
                    sender.sendMessage("用法: /mcqq mode <chat|full>");
                    return true;
                }
                switch (args[1].toLowerCase()) {
                    case "chat" -> setMode(sender, BridgeMode.CHAT);
                    case "full" -> setMode(sender, BridgeMode.FULL);
                    default -> sender.sendMessage("未知模式: " + args[1] + " (可选: chat / full)");
                }
            }
            case "status" -> {
                sender.sendMessage("[McQqBridge] 模式: " + mode);
                sender.sendMessage("[McQqBridge] 绑定群: " + (groupOpenId.get().isEmpty() ? "未绑定" : groupOpenId.get()));
                sender.sendMessage("[McQqBridge] MC->QQ: " + (mcToQq ? "开" : "关") + " | QQ->MC: " + (qqToMc ? "开" : "关"));
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void setMode(CommandSender sender, BridgeMode newMode) {
        mode = newMode;
        getConfig().set("bridge.mode", newMode.name().toLowerCase());
        saveConfig();
        sender.sendMessage("[McQqBridge] 模式已切换为: " + newMode);
        getLogger().info("Bridge mode changed to " + newMode + " by " + sender.getName());
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("[McQqBridge] 指令列表:");
        sender.sendMessage("  /mcqq mode <chat|full>  - 切换模式 (chat=只发聊天, full=聊天+系统消息)");
        sender.sendMessage("  /mcqq status           - 查看当前状态");
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (mode != BridgeMode.FULL) return;
        String playerName = event.getPlayer().getName();
        qqClient.sendGroupMessage(groupOpenId.get(), "[进服] " + playerName + " 加入了游戏");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (mode != BridgeMode.FULL) return;
        String playerName = event.getPlayer().getName();
        qqClient.sendGroupMessage(groupOpenId.get(), "[退服] " + playerName + " 退出了游戏");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (mode != BridgeMode.FULL) return;
        if (event.deathMessage() == null) return;
        String deathText = PlainTextComponentSerializer.plainText().serialize(event.deathMessage());
        qqClient.sendGroupMessage(groupOpenId.get(), "[死亡] " + deathText);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (mode != BridgeMode.FULL) return;
        var display = event.getAdvancement().getDisplay();
        if (display == null || !display.doesAnnounceToChat()) return;
        String playerName = event.getPlayer().getName();
        String title = PlainTextComponentSerializer.plainText().serialize(display.title());
        qqClient.sendGroupMessage(groupOpenId.get(), "[成就] " + playerName + " 获得了成就「" + title + "」");
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        mcToQq = config.getBoolean("bridge.mc-to-qq", true);
        qqToMc = config.getBoolean("bridge.qq-to-mc", true);
        mcFormat = config.getString("bridge.mc-format", "[MC] <{player}> {message}");
        qqFormat = config.getString("bridge.qq-format", "[QQ] <{nickname}> {message}");
        String modeStr = config.getString("bridge.mode", "chat");
        try {
            mode = BridgeMode.valueOf(modeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            mode = BridgeMode.CHAT;
        }
    }
}
