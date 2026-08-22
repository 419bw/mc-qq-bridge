package com.mcqqbridge.command;

import com.mcqqbridge.config.BridgeConfig;
import com.mcqqbridge.config.BridgeConfig.BridgeMode;
import com.mcqqbridge.report.DailyReportScheduler;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

public class McQqCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = "[McQqBridge] ";
    private static final String RESTART_HINT = "（已保存，重启服务器后生效）";

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
        if (!sub.equals("status") && !sender.hasPermission("mcqq.admin")) {
            sender.sendMessage(PREFIX + "无权限 (需要 mcqq.admin)");
            return true;
        }

        switch (sub) {
            case "mode" -> handleMode(sender, args);
            case "bridge" -> handleBridge(sender, args);
            case "format" -> handleFormat(sender, args);
            case "bind" -> handleBind(sender, args);
            case "unbind" -> {
                config.setAndSaveGroupOpenId("");
                sender.sendMessage(PREFIX + "已解除群绑定");
                logger.info("Group unbound by " + sender.getName());
            }
            case "report" -> handleReport(sender, args);
            case "map" -> handleMap(sender, args);
            case "trail" -> handleTrail(sender, args);
            case "terrain" -> handleTerrain(sender, args);
            case "ai" -> handleAi(sender, args);
            case "qq" -> handleQq(sender, args);
            case "reload" -> {
                plugin.reloadConfig();
                config.load();
                sender.sendMessage(PREFIX + "配置已从磁盘重新加载（热生效项立即生效，其余重启后生效）");
                logger.info("Config reloaded by " + sender.getName());
            }
            case "status" -> sendStatus(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    // ---- 子命令处理 ----

    private void handleMode(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("用法: /mcqq mode <chat|full>");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "chat" -> setMode(sender, BridgeMode.CHAT);
            case "full" -> setMode(sender, BridgeMode.FULL);
            default -> sender.sendMessage("未知模式: " + args[1] + " (可选: chat / full)");
        }
    }

    private void handleBridge(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("用法: /mcqq bridge <mc2qq|qq2mc> <on|off>");
            return;
        }
        Boolean on = parseOnOff(args[2]);
        if (on == null) {
            sender.sendMessage("取值应为 on 或 off: " + args[2]);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "mc2qq" -> {
                config.setMcToQq(on);
                sender.sendMessage(PREFIX + "MC->QQ 转发已" + (on ? "开启" : "关闭"));
                logger.info("MC->QQ forwarding " + (on ? "enabled" : "disabled") + " by " + sender.getName());
            }
            case "qq2mc" -> {
                config.setQqToMc(on);
                sender.sendMessage(PREFIX + "QQ->MC 转发已" + (on ? "开启" : "关闭"));
                logger.info("QQ->MC forwarding " + (on ? "enabled" : "disabled") + " by " + sender.getName());
            }
            default -> sender.sendMessage("用法: /mcqq bridge <mc2qq|qq2mc> <on|off>");
        }
    }

    private void handleFormat(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("用法: /mcqq format <mc|qq> <格式文本> (占位符: {player}/{nickname} {message})");
            return;
        }
        String fmt = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        switch (args[1].toLowerCase()) {
            case "mc" -> {
                config.setMcFormat(fmt);
                sender.sendMessage(PREFIX + "MC->QQ 格式已更新: " + fmt);
                logger.info("MC->QQ format changed by " + sender.getName());
            }
            case "qq" -> {
                config.setQqFormat(fmt);
                sender.sendMessage(PREFIX + "QQ->MC 格式已更新: " + fmt);
                logger.info("QQ->MC format changed by " + sender.getName());
            }
            default -> sender.sendMessage("用法: /mcqq format <mc|qq> <格式文本>");
        }
    }

    private void handleBind(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("用法: /mcqq bind <group-openid> (当前: "
                    + (config.getGroupOpenId().isEmpty() ? "未绑定" : config.getGroupOpenId()) + ")");
            return;
        }
        config.setAndSaveGroupOpenId(args[1]);
        sender.sendMessage(PREFIX + "已绑定群: " + args[1]);
        logger.info("Group bound to " + args[1] + " by " + sender.getName());
    }

    private void handleReport(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendHelp(sender);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "now" -> {
                reportScheduler.runNow();
                sender.sendMessage(PREFIX + "正在生成今日日报，稍后推送到群...");
            }
            case "toggle" -> {
                boolean next = !reportScheduler.isEnabled();
                reportScheduler.setEnabled(next);
                config.setReportEnabled(next);
                sender.sendMessage(PREFIX + "每日日报已" + (next ? "开启" : "关闭"));
                logger.info("Daily report " + (next ? "enabled" : "disabled") + " by " + sender.getName());
            }
            case "time" -> {
                if (args.length < 3) {
                    sender.sendMessage("用法: /mcqq report time <HH:MM>");
                    return;
                }
                if (!args[2].matches("^([01]?\\d|2[0-3]):[0-5]\\d$")) {
                    sender.sendMessage("时间格式应为 HH:MM，如 23:00");
                    return;
                }
                config.setReportTime(args[2]);
                sender.sendMessage(PREFIX + "日报时间已设为 " + args[2] + RESTART_HINT);
                logger.info("Daily report time changed to " + args[2] + " by " + sender.getName());
            }
            case "retention" -> {
                Integer days = parseIntArg(sender, args, 2, "保留天数", 1, 3650);
                if (days == null) return;
                config.setRetentionDays(days);
                sender.sendMessage(PREFIX + "数据保留天数已设为 " + days + RESTART_HINT);
                logger.info("Retention days changed to " + days + " by " + sender.getName());
            }
            default -> sendHelp(sender);
        }
    }

    private void handleMap(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("用法: /mcqq map <maxwidth|padding> <数值>");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "maxwidth" -> {
                Integer v = parseIntArg(sender, args, 2, "最大宽度", 64, 8192);
                if (v == null) return;
                config.setMapMaxWidth(v);
                sender.sendMessage(PREFIX + "地图最大宽度已设为 " + v + "px" + RESTART_HINT);
                logger.info("Map max-width changed to " + v + " by " + sender.getName());
            }
            case "padding" -> {
                Integer v = parseIntArg(sender, args, 2, "边距", 0, 1024);
                if (v == null) return;
                config.setMapPadding(v);
                sender.sendMessage(PREFIX + "地图边距已设为 " + v + " 格（下次出报生效）");
                logger.info("Map padding changed to " + v + " by " + sender.getName());
            }
            default -> sender.sendMessage("用法: /mcqq map <maxwidth|padding> <数值>");
        }
    }

    private void handleTrail(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("用法: /mcqq trail <interval|stay> <秒>");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "interval" -> {
                Integer v = parseIntArg(sender, args, 2, "采样间隔(秒)", 1, 600);
                if (v == null) return;
                config.setTrailIntervalSec(v);
                sender.sendMessage(PREFIX + "轨迹采样间隔已设为 " + v + " 秒" + RESTART_HINT);
                logger.info("Trail interval changed to " + v + "s by " + sender.getName());
            }
            case "stay" -> {
                Integer v = parseIntArg(sender, args, 2, "停留判定(秒)", 5, 3600);
                if (v == null) return;
                config.setStayThresholdSec(v);
                sender.sendMessage(PREFIX + "停留判定阈值已设为 " + v + " 秒" + RESTART_HINT);
                logger.info("Stay threshold changed to " + v + "s by " + sender.getName());
            }
            default -> sender.sendMessage("用法: /mcqq trail <interval|stay> <秒>");
        }
    }

    private void handleTerrain(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("用法: /mcqq terrain <on|off>");
            return;
        }
        Boolean on = parseOnOff(args[1]);
        if (on == null) {
            sender.sendMessage("取值应为 on 或 off: " + args[1]);
            return;
        }
        config.setTerrainEnabled(on);
        sender.sendMessage(PREFIX + "地形底图已" + (on ? "开启" : "关闭") + RESTART_HINT);
        logger.info("Terrain basemap " + (on ? "enabled" : "disabled") + " by " + sender.getName());
    }

    private void handleAi(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("用法: /mcqq ai <enable|disable|model|baseurl|apikey|timeout> [值]");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "enable" -> {
                config.setAiReportEnabled(true);
                sender.sendMessage(PREFIX + "AI 日报总结已开启" + RESTART_HINT);
                logger.info("AI report enabled by " + sender.getName());
            }
            case "disable" -> {
                config.setAiReportEnabled(false);
                sender.sendMessage(PREFIX + "AI 日报总结已关闭" + RESTART_HINT);
                logger.info("AI report disabled by " + sender.getName());
            }
            case "model", "baseurl", "apikey" -> {
                if (args.length < 3) {
                    sender.sendMessage("用法: /mcqq ai " + args[1].toLowerCase() + " <值>");
                    return;
                }
                switch (args[1].toLowerCase()) {
                    case "model" -> config.setAiModel(args[2]);
                    case "baseurl" -> config.setAiBaseUrl(args[2]);
                    case "apikey" -> config.setAiApiKey(args[2]);
                }
                sender.sendMessage(PREFIX + "AI " + args[1].toLowerCase() + " 已设为 " + args[2] + RESTART_HINT);
                logger.info("AI " + args[1].toLowerCase() + " changed by " + sender.getName());
            }
            case "timeout" -> {
                Integer v = parseIntArg(sender, args, 2, "超时(秒)", 1, 600);
                if (v == null) return;
                config.setAiTimeoutSec(v);
                sender.sendMessage(PREFIX + "AI 超时已设为 " + v + " 秒" + RESTART_HINT);
                logger.info("AI timeout changed to " + v + "s by " + sender.getName());
            }
            default -> sender.sendMessage("用法: /mcqq ai <enable|disable|model|baseurl|apikey|timeout> [值]");
        }
    }

    private void handleQq(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("用法: /mcqq qq <appid|appsecret> <值>");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "appid" -> {
                config.setAppId(args[2]);
                sender.sendMessage(PREFIX + "QQ AppID 已设置" + RESTART_HINT);
                logger.info("QQ app-id changed by " + sender.getName());
            }
            case "appsecret" -> {
                config.setAppSecret(args[2]);
                sender.sendMessage(PREFIX + "QQ AppSecret 已设置" + RESTART_HINT);
                logger.info("QQ app-secret changed by " + sender.getName());
            }
            default -> sender.sendMessage("用法: /mcqq qq <appid|appsecret> <值>");
        }
    }

    // ---- Tab 补全 ----

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission("mcqq.admin");
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            subs.add("status");
            if (admin) {
                subs.addAll(List.of("mode", "bridge", "format", "bind", "unbind",
                        "report", "map", "trail", "terrain", "ai", "qq", "reload"));
            }
            return match(subs, args[0]);
        }
        if (!admin) {
            return List.of();
        }
        String sub = args[0].toLowerCase();
        if (args.length == 2) {
            return switch (sub) {
                case "mode" -> match(List.of("chat", "full"), args[1]);
                case "bridge" -> match(List.of("mc2qq", "qq2mc"), args[1]);
                case "format" -> match(List.of("mc", "qq"), args[1]);
                case "report" -> match(List.of("now", "toggle", "time", "retention"), args[1]);
                case "map" -> match(List.of("maxwidth", "padding"), args[1]);
                case "trail" -> match(List.of("interval", "stay"), args[1]);
                case "terrain" -> match(List.of("on", "off"), args[1]);
                case "ai" -> match(List.of("enable", "disable", "model", "baseurl", "apikey", "timeout"), args[1]);
                case "qq" -> match(List.of("appid", "appsecret"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && sub.equals("bridge")) {
            return match(List.of("on", "off"), args[2]);
        }
        return List.of();
    }

    private static List<String> match(List<String> options, String prefix) {
        String p = prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }

    // ---- 状态与帮助 ----

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(PREFIX + "模式: " + config.getMode()
                + " | MC->QQ: " + (config.isMcToQq() ? "开" : "关")
                + " | QQ->MC: " + (config.isQqToMc() ? "开" : "关"));
        sender.sendMessage(PREFIX + "绑定群: " + (config.getGroupOpenId().isEmpty() ? "未绑定" : config.getGroupOpenId()));
        sender.sendMessage(PREFIX + "格式: MC-> " + config.getMcFormat());
        sender.sendMessage(PREFIX + "格式: QQ-> " + config.getQqFormat());
        sender.sendMessage(PREFIX + "日报: " + (reportScheduler.isEnabled() ? "开" : "关")
                + " 每日 " + config.getReportHour() + ":" + pad2(config.getReportMinute())
                + " | 保留 " + config.getRetentionDays() + " 天");
        sender.sendMessage(PREFIX + "地图: 最大宽度 " + config.getMapMaxWidth() + "px"
                + " | 边距 " + config.getMapPadding() + " 格"
                + " | 地形底图: " + (config.isTerrainEnabled() ? "开" : "关"));
        sender.sendMessage(PREFIX + "轨迹: 采样间隔 " + config.getTrailIntervalMs() / 1000
                + "s | 停留判定 " + config.getStayThresholdMs() / 1000 + "s");
        sender.sendMessage(PREFIX + "AI 总结: " + (config.isAiReportEnabled() ? "开" : "关")
                + " | model: " + config.getAiModel()
                + " | key: " + (config.getAiApiKey().isEmpty() ? "未配置" : "已配置")
                + " | 超时 " + config.getAiTimeoutSec() + "s");
        sender.sendMessage(PREFIX + "QQ: appid " + (config.getAppId().isEmpty() ? "未配置" : "已配置")
                + " | secret " + (config.getAppSecret().isEmpty() ? "未配置" : "已配置"));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(PREFIX + "指令列表:");
        sender.sendMessage("  /mcqq status                     - 查看当前状态与配置");
        sender.sendMessage("  /mcqq mode <chat|full>           - 切换模式 (chat=只发聊天, full=聊天+系统消息)");
        sender.sendMessage("  /mcqq bridge <mc2qq|qq2mc> <on|off> - 开关转发方向");
        sender.sendMessage("  /mcqq format <mc|qq> <格式文本>   - 设置转发格式 (占位符 {player}/{nickname} {message})");
        sender.sendMessage("  /mcqq bind <openid> | unbind     - 手动绑定/解除 QQ 群");
        sender.sendMessage("  /mcqq report now|toggle          - 立即出日报 / 开关每日日报");
        sender.sendMessage("  /mcqq report time <HH:MM>        - 设置日报时间 (重启生效)");
        sender.sendMessage("  /mcqq report retention <天数>     - 设置数据保留天数 (重启生效)");
        sender.sendMessage("  /mcqq map maxwidth|padding <数值> - 地图最大宽度(重启生效)/边距(格)");
        sender.sendMessage("  /mcqq trail interval|stay <秒>   - 轨迹采样间隔/停留判定阈值 (重启生效)");
        sender.sendMessage("  /mcqq terrain <on|off>           - 地形底图开关 (重启生效)");
        sender.sendMessage("  /mcqq ai <enable|disable|model|baseurl|apikey|timeout> [值] - AI 总结设置 (重启生效)");
        sender.sendMessage("  /mcqq qq appid|appsecret <值>    - QQ 机器人凭据 (重启生效)");
        sender.sendMessage("  /mcqq reload                     - 从磁盘重新加载配置");
    }

    // ---- 工具 ----

    private void setMode(CommandSender sender, BridgeMode newMode) {
        config.setMode(newMode);
        sender.sendMessage(PREFIX + "模式已切换为: " + newMode);
        logger.info("Bridge mode changed to " + newMode + " by " + sender.getName());
    }

    private Boolean parseOnOff(String s) {
        return switch (s.toLowerCase()) {
            case "on", "true" -> true;
            case "off", "false" -> false;
            default -> null;
        };
    }

    private Integer parseIntArg(CommandSender sender, String[] args, int idx, String name, int min, int max) {
        if (args.length <= idx) {
            sender.sendMessage("缺少参数: " + name);
            return null;
        }
        try {
            int v = Integer.parseInt(args[idx]);
            if (v < min || v > max) {
                sender.sendMessage(name + "应在 " + min + "~" + max + " 之间");
                return null;
            }
            return v;
        } catch (NumberFormatException e) {
            sender.sendMessage(name + "应为整数: " + args[idx]);
            return null;
        }
    }

    private String pad2(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }
}
