package com.example.feishuproxy.core;

import com.example.feishuproxy.model.GameStats;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GameStatsParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 真实报文的形状。数值刻意各不相同——全填 0 的话，读串了字段也看不出来。
     */
    private static final String REAL = "{\"msg_type\":\"post\",\"content\":{\"post\":{\"zh_cn\":{"
            + "\"title\":\"微凉Pro游戏数据统计 2026-09-01\",\"content\":[["
            + "{\"tag\":\"text\",\"text\":\"🎮累计对局:31 | 💰累计BP:1200\\n\"},"
            + "{\"tag\":\"text\",\"text\":\"📌通行经验:999 | 📌生存经验:450\\n\"},"
            + "{\"tag\":\"text\",\"text\":\"⭐生存等级:7 | ⚔️累计击杀:88\\n\"},"
            + "{\"tag\":\"text\",\"text\":\"💥累计伤害:12345 | ⏱️耗时:01:02:05\"}],"
            + "[{\"tag\":\"img\",\"image_key\":\"img_v3_02154\"}]]}}}}";

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 按真实报文的结构拼一条 post，每个 line 是一个 text span。 */
    private static JsonNode report(String title, String... lines) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("msg_type", "post");
        ObjectNode locale = root.putObject("content").putObject("post").putObject("zh_cn");
        locale.put("title", title);
        ArrayNode paragraph = locale.putArray("content").addArray();
        for (String line : lines) {
            paragraph.addObject().put("tag", "text").put("text", line);
        }
        return root;
    }

    @Test
    void readsEveryStatFromARealReport() {
        GameStatsParser.Snapshot snapshot = GameStatsParser.parse(json(REAL));

        assertNotNull(snapshot);
        assertEquals("2026-09-01", snapshot.getDate());
        assertEquals(Integer.valueOf(7), snapshot.getSurvivalLevel());
        assertEquals(Long.valueOf(450), snapshot.getSurvivalExp());
        assertEquals(Long.valueOf(1200), snapshot.getBp());
        assertEquals(Long.valueOf(3725), snapshot.getDurationSeconds(), "01:02:05 是 3725 秒");
    }

    @Test
    void doesNotConfuseTheBattlePassExpWithTheSurvivalExp() {
        // 两个标签都以「经验」结尾，只匹配「经验」会读到 通行经验 的 999。
        assertEquals(Long.valueOf(450), GameStatsParser.parse(json(REAL)).getSurvivalExp());
    }

    @Test
    void toleratesAReportThatOmitsSomeLabels() {
        GameStatsParser.Snapshot snapshot = GameStatsParser.parse(
                report("微凉Pro游戏数据统计 2026-09-02", "🎮累计对局:1 | 💰累计BP:10\n", "⏱️耗时:00:00:30"));

        assertNotNull(snapshot, "有 BP 和耗时就算战报");
        assertEquals(Long.valueOf(10), snapshot.getBp());
        assertNull(snapshot.getSurvivalExp(), "报文里没有这一项");
        assertNull(snapshot.getSurvivalLevel());
    }

    @Test
    void aPostWithNoNumbersIsNotAStatsReport() {
        // 只有日期没有数值的 post 如果被当成战报，就会挡住真正的上一条，把增量算成 null。
        assertNull(GameStatsParser.parse(report("微凉Pro游戏数据统计 2026-09-03", "今天休战")));
    }

    @Test
    void returnsNullForAnythingThatIsNotAPost() {
        assertNull(GameStatsParser.parse(null));
        assertNull(GameStatsParser.parse(json("{\"msg_type\":\"text\",\"content\":{\"text\":\"累计BP:5\"}}")));
        assertNull(GameStatsParser.parse(json("[]")));
    }

    @Test
    void subtractsThePreviousCumulativeValues() {
        GameStatsParser.Snapshot previous = GameStatsParser.parse(
                report("统计 2026-08-31", "💰累计BP:1000\n", "📌生存经验:400\n", "⏱️耗时:01:00:00"));

        GameStats stats = GameStatsParser.delta(GameStatsParser.parse(json(REAL)), previous);

        assertEquals(Long.valueOf(200), stats.getBpGained(), "1200 - 1000");
        assertEquals(Long.valueOf(50), stats.getExpGained(), "450 - 400");
        assertEquals("00:02:05", stats.getDuration(), "3725 - 3600 = 125 秒");
    }

    @Test
    void keepsTheDateAndLevelAsIsWhileDeltasNeedAPrevious() {
        GameStats stats = GameStatsParser.delta(GameStatsParser.parse(json(REAL)), null);

        assertEquals("2026-09-01", stats.getStatDate(), "日期是原值，不做差分");
        assertEquals(Integer.valueOf(7), stats.getSurvivalLevel(), "生存等级也是原值");
        assertNull(stats.getBpGained(), "没有上一条就算不出增量，不能拿 0 顶替");
        assertNull(stats.getExpGained());
        assertNull(stats.getDuration());
    }

    @Test
    void formatsADurationDeltaThatSpansHours() {
        GameStatsParser.Snapshot previous = GameStatsParser.parse(report("统计", "⏱️耗时:00:00:00"));

        GameStats stats = GameStatsParser.delta(GameStatsParser.parse(json(REAL)), previous);

        assertEquals("01:02:05", stats.getDuration());
    }

    @Test
    void keepsANegativeDeltaWhenTheCumulativeWentBackwards() {
        // 赛季重置会让累计值倒退。悄悄夹到 0 会把「重置发生过」这件事抹掉。
        GameStatsParser.Snapshot previous = GameStatsParser.parse(
                report("统计", "💰累计BP:9999\n", "⏱️耗时:99:00:00"));

        GameStats stats = GameStatsParser.delta(GameStatsParser.parse(json(REAL)), previous);

        assertEquals(Long.valueOf(-8799), stats.getBpGained());
        assertEquals("-97:57:55", stats.getDuration());
    }

    @Test
    void computesADeltaOnlyWhenBothSidesCarryTheField() {
        GameStatsParser.Snapshot previous = GameStatsParser.parse(report("统计", "💰累计BP:1000"));

        GameStats stats = GameStatsParser.delta(GameStatsParser.parse(json(REAL)), previous);

        assertEquals(Long.valueOf(200), stats.getBpGained());
        assertNull(stats.getExpGained(), "上一条没有生存经验，差值无从谈起");
        assertNull(stats.getDuration());
    }
}
