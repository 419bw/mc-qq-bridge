package com.mcqqbridge.config;

/**
 * 配置项注册表：配置项的生效范围、值类型、校验范围与提示文案的单一事实来源。
 * 命令层据此解析/校验/发提示，{@link BridgeConfig#apply} 据此写内存与 config.yml。
 * mode / report.enabled / group-openid 有各自的运行时语义（枚举解析、调度器联动、
 * 自动探测），不走本注册表，仍由专用命令处理。
 */
public enum ConfigItem {

    MC_TO_QQ("MC->QQ 转发", "bridge.mc-to-qq", ApplyScope.IMMEDIATE, ValueKind.ON_OFF),
    QQ_TO_MC("QQ->MC 转发", "bridge.qq-to-mc", ApplyScope.IMMEDIATE, ValueKind.ON_OFF),
    MC_FORMAT("MC->QQ 格式", "bridge.mc-format", ApplyScope.IMMEDIATE, ValueKind.TEXT),
    QQ_FORMAT("QQ->MC 格式", "bridge.qq-format", ApplyScope.IMMEDIATE, ValueKind.TEXT),

    REPORT_TIME("日报时间", "report.time", ApplyScope.RESTART, ValueKind.TIME),
    RETENTION_DAYS("数据保留天数", "report.retention-days", ApplyScope.RESTART, ValueKind.INT, 1, 3650, "天"),
    MAP_MAX_WIDTH("地图最大宽度", "report.map.max-width", ApplyScope.RESTART, ValueKind.INT, 64, 8192, "px"),
    MAP_PADDING("地图边距", "report.map.padding", ApplyScope.NEXT_REPORT, ValueKind.INT, 0, 1024, "格"),
    TRAIL_INTERVAL("轨迹采样间隔", "report.trail.time-threshold-sec", ApplyScope.RESTART, ValueKind.INT, 1, 600, "秒"),
    STAY_THRESHOLD("停留判定阈值", "report.trail.stay-threshold-sec", ApplyScope.RESTART, ValueKind.INT, 5, 3600, "秒"),
    TERRAIN("地形底图", "report.terrain.enabled", ApplyScope.RESTART, ValueKind.ON_OFF),

    AI_ENABLED("AI 日报总结", "report.ai.enabled", ApplyScope.RESTART, ValueKind.ON_OFF),
    AI_BASE_URL("AI base-url", "report.ai.base-url", ApplyScope.RESTART, ValueKind.TEXT),
    AI_API_KEY("AI api-key", "report.ai.api-key", ApplyScope.RESTART, ValueKind.TEXT, true),
    AI_MODEL("AI model", "report.ai.model", ApplyScope.RESTART, ValueKind.TEXT),
    AI_TIMEOUT("AI 超时", "report.ai.timeout-sec", ApplyScope.RESTART, ValueKind.INT, 1, 600, "秒"),

    QQ_APP_ID("QQ AppID", "qq.app-id", ApplyScope.RESTART, ValueKind.TEXT),
    QQ_APP_SECRET("QQ AppSecret", "qq.app-secret", ApplyScope.RESTART, ValueKind.TEXT, true);

    /** 修改后的生效范围，决定命令确认提示的后缀文案。 */
    public enum ApplyScope {
        IMMEDIATE(""),
        NEXT_REPORT("（下次出报生效）"),
        RESTART("（已保存，重启服务器后生效）");

        private final String hint;

        ApplyScope(String hint) {
            this.hint = hint;
        }

        public String hint() {
            return hint;
        }
    }

    /** 值类型，决定解析与校验方式。 */
    public enum ValueKind {
        ON_OFF, INT, TEXT, TIME
    }

    private final String label;
    private final String path;
    private final ApplyScope scope;
    private final ValueKind kind;
    private final int min;
    private final int max;
    private final String unit;
    private final boolean secret;

    ConfigItem(String label, String path, ApplyScope scope, ValueKind kind) {
        this(label, path, scope, kind, 0, 0, "", false);
    }

    ConfigItem(String label, String path, ApplyScope scope, ValueKind kind, int min, int max, String unit) {
        this(label, path, scope, kind, min, max, unit, false);
    }

    ConfigItem(String label, String path, ApplyScope scope, ValueKind kind, boolean secret) {
        this(label, path, scope, kind, 0, 0, "", secret);
    }

    ConfigItem(String label, String path, ApplyScope scope, ValueKind kind,
               int min, int max, String unit, boolean secret) {
        this.label = label;
        this.path = path;
        this.scope = scope;
        this.kind = kind;
        this.min = min;
        this.max = max;
        this.unit = unit;
        this.secret = secret;
    }

    public String label() {
        return label;
    }

    public String path() {
        return path;
    }

    public ApplyScope scope() {
        return scope;
    }

    public ValueKind kind() {
        return kind;
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    public String unit() {
        return unit;
    }

    public boolean secret() {
        return secret;
    }

    /** TIME 取值校验（HH:MM，24 小时制）。 */
    public static boolean isValidTime(String s) {
        return s.matches("^([01]?\\d|2[0-3]):[0-5]\\d$");
    }
}
