package com.example.feishuproxy.core;

import com.example.feishuproxy.model.GameStats;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从飞书 post 战报里把游戏数值抠出来。
 * <p>
 * 报文长这样，数值内嵌在文本 span 里，没有结构化字段可用：
 * <pre>
 * 🎮累计对局:0 | 💰累计BP:0
 * 📌通行经验:0 | 📌生存经验:0
 * ⭐生存等级:0 | ⚔️累计击杀:0
 * 💥累计伤害:0 | ⏱️耗时:00:00:00
 * </pre>
 * 所以先把所有文本 span 拼成一整段，再按标签正则取值。
 * <p>
 * 这里刻意不复用 {@link MessagePreview}：它的遍历方法是私有的，而且 {@code preview()} 会折叠空白
 * 并截断到 200 个码点——拿一个为「展示」而截断的字符串去解析数值，等于把正确性押在报文长度上。
 * <p>
 * 报文给的是<strong>累计值</strong>，{@link #parse} 返回的 {@link Snapshot} 就是累计快照；
 * 差分由 {@link #delta} 完成。两者形状不同，所以是两个类型。
 */
public final class GameStatsParser {

    /** 标题里的日期，例如「微凉Pro游戏数据统计 2026-09-01」。 */
    private static final Pattern DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

    /** 小时位不限宽：累计时长迟早会超过 99 小时。 */
    private static final Pattern DURATION = Pattern.compile("耗时\\s*[:：]\\s*(\\d+):(\\d{2}):(\\d{2})");

    private static final Pattern BP = label("累计BP");
    private static final Pattern SURVIVAL_EXP = label("生存经验");
    private static final Pattern SURVIVAL_LEVEL = label("生存等级");

    private static final int SECONDS_PER_HOUR = 3600;
    private static final int SECONDS_PER_MINUTE = 60;

    private GameStatsParser() {
    }

    /**
     * 用标签全称匹配。只写「经验」会把「通行经验」也算进来，那是另一套数值体系。
     * 值允许带负号，是为了如实读出报文，而不是替它纠错。
     */
    private static Pattern label(String name) {
        return Pattern.compile(Pattern.quote(name) + "\\s*[:：]\\s*(-?\\d+)");
    }

    /**
     * 解析一条 post 报文的累计快照。
     *
     * @param body 整条消息的报文，可为 null（被拒的请求没有解析结果）
     * @return 至少读到一项数值时返回快照，否则返回 null——「不是战报」和「全是 0 的战报」必须能区分
     */
    public static Snapshot parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            return null;
        }
        JsonNode post = body.path("content").path("post");
        if (!post.isObject()) {
            return null;
        }

        // post 按语言区域作键。取第一个有标题的，和 MessagePreview 的做法保持一致。
        String title = null;
        StringBuilder text = new StringBuilder();
        for (JsonNode locale : post) {
            if (title == null) {
                String candidate = locale.path("title").asText(null);
                if (candidate != null && !candidate.isEmpty()) {
                    title = candidate;
                }
            }
            appendSpans(locale.path("content"), text);
        }

        String flat = text.toString();
        Long bp = number(BP, flat);
        Long exp = number(SURVIVAL_EXP, flat);
        Long duration = durationSeconds(flat);
        Long level = number(SURVIVAL_LEVEL, flat);

        // 只有日期没有任何数值的 post 不算战报——否则它会被当成「上一条」，把真正的上一条挡在后面。
        if (bp == null && exp == null && duration == null) {
            return null;
        }
        return new Snapshot(date(title), level == null ? null : level.intValue(), exp, bp, duration);
    }

    /**
     * 把两个累计快照差分成一行可入库的统计数据。
     *
     * @param previous 同 botKey 的上一条战报，没有则为 null
     * @return {@code current} 为 null 时返回 null
     */
    public static GameStats delta(Snapshot current, Snapshot previous) {
        if (current == null) {
            return null;
        }
        Snapshot before = previous == null ? Snapshot.EMPTY : previous;
        return new GameStats(current.date, current.survivalLevel,
                diff(current.survivalExp, before.survivalExp),
                diff(current.bp, before.bp),
                formatDuration(diff(current.durationSeconds, before.durationSeconds)));
    }

    /** 两边都得有值才算得出增量。缺一边就是「算不出」，返回 null 而不是拿 0 顶替。 */
    private static Long diff(Long current, Long previous) {
        return current == null || previous == null ? null : current - previous;
    }

    /** 赛季重置会让累计值倒退，此时如实带上负号——悄悄夹到 0 会把重置这件事掩盖掉。 */
    private static String formatDuration(Long seconds) {
        if (seconds == null) {
            return null;
        }
        long abs = Math.abs(seconds);
        return String.format(Locale.ROOT, "%s%02d:%02d:%02d", seconds < 0 ? "-" : "",
                abs / SECONDS_PER_HOUR, (abs % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE, abs % SECONDS_PER_MINUTE);
    }

    /** {@code content.post.<locale>.content} 是段落数组，每个段落又是 span 数组。 */
    private static void appendSpans(JsonNode paragraphs, StringBuilder out) {
        for (JsonNode paragraph : paragraphs) {
            for (JsonNode span : paragraph) {
                String tag = span.path("tag").asText("");
                if ("text".equals(tag) || "a".equals(tag)) {
                    // 补一个空格，免得相邻 span 首尾相接拼出并不存在的数字。
                    out.append(span.path("text").asText("")).append(' ');
                }
            }
        }
    }

    private static Long number(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            // 位数多到 long 装不下。宁可当作没读到，也不要写一个错的数进库。
            return null;
        }
    }

    private static Long durationSeconds(String text) {
        Matcher matcher = DURATION.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1)) * SECONDS_PER_HOUR
                    + Long.parseLong(matcher.group(2)) * SECONDS_PER_MINUTE
                    + Long.parseLong(matcher.group(3));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String date(String title) {
        if (title == null) {
            return null;
        }
        Matcher matcher = DATE.matcher(title);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 一条战报里的累计值原样快照。差分之前的中间形态，不入库。 */
    public static final class Snapshot {

        private static final Snapshot EMPTY = new Snapshot(null, null, null, null, null);

        private final String date;
        private final Integer survivalLevel;
        private final Long survivalExp;
        private final Long bp;
        private final Long durationSeconds;

        Snapshot(String date, Integer survivalLevel, Long survivalExp, Long bp, Long durationSeconds) {
            this.date = date;
            this.survivalLevel = survivalLevel;
            this.survivalExp = survivalExp;
            this.bp = bp;
            this.durationSeconds = durationSeconds;
        }

        public String getDate() {
            return date;
        }

        public Integer getSurvivalLevel() {
            return survivalLevel;
        }

        public Long getSurvivalExp() {
            return survivalExp;
        }

        public Long getBp() {
            return bp;
        }

        public Long getDurationSeconds() {
            return durationSeconds;
        }
    }
}
