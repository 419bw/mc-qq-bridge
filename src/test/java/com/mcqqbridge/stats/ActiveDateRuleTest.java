package com.mcqqbridge.stats;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 本地测试入口（纯 JDK，无 Bukkit 依赖，不打进 jar）：
 * 验证统计窗口归属日规则 PlayerTracker.activeDateFor。
 * 运行：java -cp target/classes;target/test-classes com.mcqqbridge.stats.ActiveDateRuleTest
 */
public class ActiveDateRuleTest {

    public static void main(String[] args) {
        check("日报前（15:00）", LocalDateTime.of(2026, 8, 16, 15, 0), 23, 0,
                LocalDate.of(2026, 8, 16));
        check("日报时刻整点", LocalDateTime.of(2026, 8, 16, 23, 0, 0), 23, 0,
                LocalDate.of(2026, 8, 17));
        check("日报后（23:30）", LocalDateTime.of(2026, 8, 16, 23, 30), 23, 0,
                LocalDate.of(2026, 8, 17));
        check("午夜后（00:30，窗口属明日日报）", LocalDateTime.of(2026, 8, 17, 0, 30), 23, 0,
                LocalDate.of(2026, 8, 17));
        check("午夜整点（00:00:00）", LocalDateTime.of(2026, 8, 17, 0, 0, 0), 23, 0,
                LocalDate.of(2026, 8, 17));
        check("日报设午夜后时（00:04，属今日）", LocalDateTime.of(2026, 8, 16, 0, 4), 0, 5,
                LocalDate.of(2026, 8, 16));
        check("日报设午夜后时（00:06，已过日报）", LocalDateTime.of(2026, 8, 16, 0, 6), 0, 5,
                LocalDate.of(2026, 8, 17));
        System.out.println("ActiveDateRuleTest: all PASS");
    }

    private static void check(String name, LocalDateTime now, int hour, int minute, LocalDate expected) {
        LocalDate actual = PlayerTracker.activeDateFor(now, hour, minute);
        if (!actual.equals(expected)) {
            throw new AssertionError(name + ": expected " + expected + " but got " + actual
                    + " (now=" + now + ", report=" + hour + ":" + minute + ")");
        }
        System.out.println("PASS " + name + " -> " + actual);
    }
}
