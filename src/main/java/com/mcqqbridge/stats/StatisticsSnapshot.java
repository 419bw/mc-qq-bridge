package com.mcqqbridge.stats;

import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录玩家进服时的原版统计基线，退服/结算时计算当日增量。
 * 仅跟踪无参的 CUSTOM 统计（可直接 getStatistic 读取）。
 * 带类型的统计（如挖掘/放置方块，需 Material 参数）暂不跟踪，后续按需扩展。
 */
public class StatisticsSnapshot {

    private static final Statistic[] TRACKED = {
            Statistic.DEATHS,
            Statistic.MOB_KILLS,
            Statistic.PLAYER_KILLS,
            Statistic.JUMP,
            Statistic.WALK_ONE_CM,
            Statistic.FLY_ONE_CM,
            Statistic.SWIM_ONE_CM,
            Statistic.FALL_ONE_CM,
            Statistic.DAMAGE_DEALT,
            Statistic.DAMAGE_TAKEN
    };

    private final Map<UUID, Map<Statistic, Integer>> baselines = new ConcurrentHashMap<>();

    public void recordBaseline(Player player) {
        Map<Statistic, Integer> base = new EnumMap<>(Statistic.class);
        for (Statistic s : TRACKED) {
            base.put(s, player.getStatistic(s));
        }
        baselines.put(player.getUniqueId(), base);
    }

    /** 退服时调用：计算自进服以来的增量并移除基线。 */
    public Map<Statistic, Integer> computeAndRemoveDelta(Player player) {
        Map<Statistic, Integer> base = baselines.remove(player.getUniqueId());
        return delta(player, base);
    }

    /** 结算时对在服玩家调用：计算自进服以来的当前增量，不移除基线。 */
    public Map<Statistic, Integer> currentDelta(Player player) {
        Map<Statistic, Integer> base = baselines.get(player.getUniqueId());
        return delta(player, base);
    }

    public void discard(UUID playerId) {
        baselines.remove(playerId);
    }

    private Map<Statistic, Integer> delta(Player player, Map<Statistic, Integer> base) {
        Map<Statistic, Integer> result = new EnumMap<>(Statistic.class);
        if (base == null) {
            return result;
        }
        for (Statistic s : TRACKED) {
            int d = player.getStatistic(s) - base.getOrDefault(s, 0);
            if (d > 0) {
                result.put(s, d);
            }
        }
        return result;
    }
}
