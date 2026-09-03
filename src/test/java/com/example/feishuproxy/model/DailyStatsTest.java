package com.example.feishuproxy.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 数据统计聚合模型的单元测试：时长解析/格式化与累加语义。 */
class DailyStatsTest {

    @Test
    void parsesDurations() {
        assertEquals(Long.valueOf(3723L), DailyStats.parseDurationSeconds("01:02:03"));
        assertEquals(Long.valueOf(360000L), DailyStats.parseDurationSeconds("100:00:00"),
                "小时位不限宽，累计时长迟早超过 99 小时");
        assertEquals(Long.valueOf(-900L), DailyStats.parseDurationSeconds("-00:15:00"),
                "赛季重置导致累计值倒退时带负号");
        assertNull(DailyStats.parseDurationSeconds(null));
        assertNull(DailyStats.parseDurationSeconds(""));
        assertNull(DailyStats.parseDurationSeconds("garbage"));
        assertNull(DailyStats.parseDurationSeconds("12:34"), "少了秒位，解析失败");
    }

    @Test
    void formatsDurations() {
        assertEquals("01:02:03", DailyStats.formatDurationSeconds(3723L));
        assertEquals("100:00:00", DailyStats.formatDurationSeconds(360000L));
        assertEquals("-00:15:00", DailyStats.formatDurationSeconds(-900L));
    }

    @Test
    void accumulatesAndSkipsNulls() {
        DailyStats stats = new DailyStats("2026-09-02", "dev");
        stats.add(50L, 200L, "00:20:00");   // 三项齐全
        stats.add(null, 150L, null);        // 缺经验与时长
        stats.add(30L, null, "00:15:00");   // 缺 BP

        assertEquals(Long.valueOf(80L), stats.getExp(), "只累加非 null 的经验");
        assertEquals(Long.valueOf(350L), stats.getBp());
        assertEquals("00:35:00", stats.getDuration());
        assertEquals(2100L, stats.getDurationSeconds());
        assertEquals(3, stats.getReports());
    }

    @Test
    void nullWhenNothingAccumulated() {
        DailyStats stats = new DailyStats("2026-09-02", "dev");
        stats.add(null, null, null);

        assertNull(stats.getExp());
        assertNull(stats.getBp());
        assertNull(stats.getDuration(), "没有一行解析出时长，累计时长应为 null 而非 00:00:00");
        assertEquals(0L, stats.getDurationSeconds());
        assertEquals(1, stats.getReports());
    }
}
