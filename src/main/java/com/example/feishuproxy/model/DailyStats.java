package com.example.feishuproxy.model;

import java.util.Locale;

/**
 * 数据统计页的一条聚合结果：{@code message_log} 按「落库日期（yyyy-MM-dd）× 目标 bot」分组，
 * 汇总当日新增生存经验、新增 BP 与累计运行时长。
 * <p>
 * 三列在单行内都可能为 null（缺上一条可比对、或报文缺项）。累加时只把非 null 的列算进来：
 * 某一组若没有一行能算出经验/BP，对应字段保持 null（「算不出」），而不是 0（「没涨」）。
 * 时长列同理，只有至少一行解析出时长才输出 {@link #getDuration()}。
 */
public class DailyStats {

    private static final int SECONDS_PER_HOUR = 3600;
    private static final int SECONDS_PER_MINUTE = 60;

    private final String date;
    private final String botKey;
    private Long exp;
    private Long bp;
    private long durationSeconds;
    private boolean hasDuration;
    private int reports;

    public DailyStats(String date, String botKey) {
        this.date = date;
        this.botKey = botKey;
    }

    /** 累加一行战报。某列 null 表示这一行算不出该项，跳过不影响其余列。 */
    public void add(Long expGained, Long bpGained, String duration) {
        if (expGained != null) {
            exp = (exp == null ? 0L : exp) + expGained;
        }
        if (bpGained != null) {
            bp = (bp == null ? 0L : bp) + bpGained;
        }
        Long seconds = parseDurationSeconds(duration);
        if (seconds != null) {
            durationSeconds += seconds;
            hasDuration = true;
        }
        reports++;
    }

    /** 落库日期，{@code yyyy-MM-dd}。 */
    public String getDate() {
        return date;
    }

    /** 目标机器人（message_log.bot_keys 的原值）。 */
    public String getBotKey() {
        return botKey;
    }

    /** 当日获取总经验；该组没有一行能算出经验增量时为 null。 */
    public Long getExp() {
        return exp;
    }

    /** 当日获取总 BP；该组没有一行能算出 BP 增量时为 null。 */
    public Long getBp() {
        return bp;
    }

    /** 当日累计运行时长，{@code HH:MM:SS}；赛季重置导致累计值倒退时可能带负号。 */
    public String getDuration() {
        return hasDuration ? formatDurationSeconds(durationSeconds) : null;
    }

    /** 当日累计运行时长（秒），供前端排序 / 数值比较。 */
    public long getDurationSeconds() {
        return durationSeconds;
    }

    /** 参与聚合的战报行数。 */
    public int getReports() {
        return reports;
    }

    /** {@code HH:MM:SS}（小时位不限宽，允许前导负号）→ 秒；解析失败返回 null。 */
    static Long parseDurationSeconds(String duration) {
        if (duration == null) {
            return null;
        }
        String s = duration.trim();
        if (s.isEmpty()) {
            return null;
        }
        boolean negative = s.charAt(0) == '-';
        if (negative) {
            s = s.substring(1);
        }
        String[] parts = s.split(":");
        if (parts.length != 3) {
            return null;
        }
        try {
            long hours = Long.parseLong(parts[0]);
            long minutes = Long.parseLong(parts[1]);
            long seconds = Long.parseLong(parts[2]);
            long total = hours * SECONDS_PER_HOUR + minutes * SECONDS_PER_MINUTE + seconds;
            return negative ? -total : total;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 秒 → {@code HH:MM:SS}，与 {@code GameStatsParser} 落库的时长格式保持一致。 */
    static String formatDurationSeconds(long seconds) {
        long abs = Math.abs(seconds);
        return String.format(Locale.ROOT, "%s%02d:%02d:%02d", seconds < 0 ? "-" : "",
                abs / SECONDS_PER_HOUR, (abs % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE, abs % SECONDS_PER_MINUTE);
    }
}
