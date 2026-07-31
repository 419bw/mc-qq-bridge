package com.mcqqbridge.report;

import com.mcqqbridge.stats.DailyRecord.GameEvent;
import com.mcqqbridge.stats.DailyRecord.PlayerSnapshot;
import com.mcqqbridge.stats.DailyRecord.Stay;
import org.bukkit.Statistic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 将快照与在线玩家未结算部分合并为日报摘要，并格式化为纯文本。
 * 在线玩家的统计/时长在主线程采集为 OnlineSnapshot 后传入，本类不访问 Bukkit 玩家数据，可在异步线程运行。
 */
public class ReportFormatter {

    public record OnlineSnapshot(UUID id, String name, Map<Statistic, Integer> delta, Long joinTime) {}

    public record PlayerSummary(
            String name,
            long playtimeMinutes,
            Map<String, Integer> stats,
            int chatCount,
            long longestStayMinutes,
            int deathCount,
            int achievementCount) {}

    public record ReportData(List<PlayerSummary> summaries, List<String> achievements, List<String> deaths) {}

    private static final int DETAIL_LIMIT = 5;

    public ReportData buildSummaries(Map<String, PlayerSnapshot> snap,
                                     List<OnlineSnapshot> online,
                                     long stayThresholdMs,
                                     long now) {
        Map<String, Long> onlineJoin = new HashMap<>();
        Map<String, Map<Statistic, Integer>> onlineDelta = new HashMap<>();
        Set<String> onlineNames = new HashSet<>();
        for (OnlineSnapshot os : online) {
            onlineNames.add(os.name);
            onlineJoin.put(os.name, os.joinTime);
            onlineDelta.put(os.name, os.delta);
        }

        List<PlayerSummary> summaries = new ArrayList<>();
        List<String> achievements = new ArrayList<>();
        List<String> deaths = new ArrayList<>();

        for (Map.Entry<String, PlayerSnapshot> e : snap.entrySet()) {
            String name = e.getKey();
            PlayerSnapshot s = e.getValue();

            long playMs = s.playtimeMs();
            Long jt = onlineJoin.get(name);
            if (jt != null) {
                playMs += Math.max(0, now - jt);
            }

            Map<String, Integer> stats = new HashMap<>(s.stats());
            Map<Statistic, Integer> od = onlineDelta.get(name);
            if (od != null) {
                od.forEach((st, v) -> stats.merge(st.name(), v, Integer::sum));
            }

            long longest = 0;
            for (Stay st : s.stays()) {
                longest = Math.max(longest, st.minutes());
            }
            if (onlineNames.contains(name) && !s.trail().isEmpty()) {
                long lastT = s.trail().get(s.trail().size() - 1).t();
                long tailMs = now - lastT;
                if (tailMs >= stayThresholdMs) {
                    longest = Math.max(longest, tailMs / 60000);
                }
            }

            int death = 0;
            int adv = 0;
            for (GameEvent ev : s.events()) {
                if ("death".equals(ev.type())) {
                    death++;
                    if (deaths.size() < DETAIL_LIMIT && !ev.text().isEmpty()) {
                        deaths.add(ev.text());
                    }
                } else if ("advancement".equals(ev.type())) {
                    adv++;
                    if (achievements.size() < DETAIL_LIMIT) {
                        achievements.add(name + " 获得「" + ev.text() + "」");
                    }
                }
            }

            summaries.add(new PlayerSummary(name, playMs / 60000, stats, s.chatCount(), longest, death, adv));
        }

        // 在线但当日尚无任何记录的玩家（仅时长/统计）
        for (OnlineSnapshot os : online) {
            if (!snap.containsKey(os.name)) {
                long playMs = os.joinTime != null ? Math.max(0, now - os.joinTime) : 0;
                Map<String, Integer> stats = new HashMap<>();
                os.delta.forEach((st, v) -> stats.merge(st.name(), v, Integer::sum));
                summaries.add(new PlayerSummary(os.name, playMs / 60000, stats, 0, 0, 0, 0));
            }
        }

        return new ReportData(summaries, achievements, deaths);
    }

    public String format(ReportData data, String date) {
        StringBuilder sb = new StringBuilder();
        sb.append("【MC 日报】").append(date).append('\n');

        boolean any = false;
        for (PlayerSummary p : data.summaries()) {
            if (!hasActivity(p)) {
                continue;
            }
            any = true;
            sb.append("- ").append(p.name).append("：在线 ").append(p.playtimeMinutes()).append(" 分钟");
            appendIfPositive(sb, p.chatCount(), " 聊天 ", " 条");
            appendIfPositive(sb, p.deathCount(), " 死亡 ", "");
            appendIfPositive(sb, p.stats().getOrDefault("MOB_KILLS", 0), " 击杀 ", "");
            int walkCm = p.stats().getOrDefault("WALK_ONE_CM", 0);
            if (walkCm > 0) {
                sb.append(String.format(" 行走 %.1fkm", walkCm / 100000.0));
            }
            if (p.longestStayMinutes() > 0) {
                sb.append(" 最长停留 ").append(p.longestStayMinutes()).append(" 分钟");
            }
            sb.append('\n');
        }
        if (!any) {
            sb.append("今日暂无活动记录。");
        }

        if (!data.achievements().isEmpty()) {
            sb.append('\n').append("今日成就：");
            sb.append(String.join("；", data.achievements()));
        }
        if (!data.deaths().isEmpty()) {
            sb.append('\n').append("死亡记录：");
            sb.append(String.join("；", data.deaths()));
        }

        return sb.toString().trim();
    }

    private boolean hasActivity(PlayerSummary p) {
        return p.playtimeMinutes() > 0
                || p.chatCount() > 0
                || p.deathCount() > 0
                || p.achievementCount() > 0
                || !p.stats().isEmpty();
    }

    private void appendIfPositive(StringBuilder sb, int value, String prefix, String suffix) {
        if (value > 0) {
            sb.append(prefix).append(value).append(suffix);
        }
    }
}
