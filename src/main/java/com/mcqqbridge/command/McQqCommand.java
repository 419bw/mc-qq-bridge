package com.mcqqbridge.command;

import com.mcqqbridge.config.BridgeConfig;
import com.mcqqbridge.config.BridgeConfig.BridgeMode;
import com.mcqqbridge.report.DailyReportScheduler;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

public class McQqCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final BridgeConfig config;
    private final DailyReportScheduler reportScheduler;
    private final Logger logger;

    public McQqCommand(JavaPlugin plugin, BridgeConfig config, DailyReportScheduler reportScheduler) {
        this.plugin = plugin;
        this.config = config;
        this.reportScheduler = reportScheduler;
        this.logger = plugin.getLogger();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        if ((sub.equals("mode") || sub.equals("report")) && !sender.hasPermission("mcqq.admin")) {
            sender.sendMessage("[McQqBridge] 无权限 (需要 mcqq.admin)");
            return true;
        }

        switch (sub) {
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
            case "report" -> {
                if (args.length < 2) {
                    sendHelp(sender);
                    return true;
                }
                switch (args[1].toLowerCase()) {
                    case "now" -> {
                        reportScheduler.runNow();
                        sender.sendMessage("[McQqBridge] 正在生成今日日报，稍后推送到群...");
                    }
                    case "toggle" -> {
                        boolean next = !reportScheduler.isEnabled();
                        reportScheduler.setEnabled(next);
                        config.setReportEnabled(next);
                        sender.sendMessage("[McQqBridge] 每日日报已" + (next ? "开启" : "关闭"));
                        logger.info("Daily report " + (next ? "enabled" : "disabled") + " by " + sender.getName());
                    }
                    default -> sendHelp(sender);
                }
            }
            case "status" -> {
                sender.sendMessage("[McQqBridge] 模式: " + config.getMode());
                sender.sendMessage("[McQqBridge] 绑定群: " + (config.getGroupOpenId().isEmpty() ? "未绑定" : config.getGroupOpenId()));
                sender.sendMessage("[McQqBridge] MC->QQ: " + (config.isMcToQq() ? "开" : "关") + " | QQ->MC: " + (config.isQqToMc() ? "开" : "关"));
                sender.sendMessage("[McQqBridge] 日报: " + (reportScheduler.isEnabled() ? "开" : "关")
                        + " 每日 " + config.getReportHour() + ":" + pad2(config.getReportMinute()));
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void setMode(CommandSender sender, BridgeMode newMode) {
        config.setMode(newMode);
        sender.sendMessage("[McQqBridge] 模式已切换为: " + newMode);
        logger.info("Bridge mode changed to " + newMode + " by " + sender.getName());
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("[McQqBridge] 指令列表:");
        sender.sendMessage("  /mcqq mode <chat|full>  - 切换模式 (chat=只发聊天, full=聊天+系统消息)");
        sender.sendMessage("  /mcqq report now        - 立即生成并推送今日日报");
        sender.sendMessage("  /mcqq report toggle     - 开关每日日报");
        sender.sendMessage("  /mcqq status            - 查看当前状态");
    }

    private String pad2(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }
}
