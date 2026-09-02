package com.example.feishuproxy.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagePreviewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void readsTitleAndFlattensBodyOfARichPostMessage() {
        // 一份真实的日报报文，正是这个存储存在的意义。
        JsonNode body = parse("{\"msg_type\":\"post\",\"content\":{\"post\":{\"zh_cn\":{"
                + "\"title\":\"微凉Pro游戏数据统计 2026-09-01\",\"content\":["
                + "[{\"tag\":\"text\",\"text\":\"🎮累计对局:0 | 💰累计BP:0\\n\"},"
                + "{\"tag\":\"text\",\"text\":\"📌通行经验:0\"}],"
                + "[{\"tag\":\"img\",\"image_key\":\"img_v3_02154_f8f95672\"}]]}}}}");

        assertEquals("微凉Pro游戏数据统计 2026-09-01", MessagePreview.title(body));
        assertEquals("🎮累计对局:0 | 💰累计BP:0 📌通行经验:0", MessagePreview.preview(body));
    }

    @Test
    void picksWhicheverLocaleCarriesTheTitle() {
        JsonNode body = parse("{\"msg_type\":\"post\",\"content\":{\"post\":{\"en_us\":{"
                + "\"title\":\"Daily report\",\"content\":[[{\"tag\":\"text\",\"text\":\"all good\"}]]}}}}");

        assertEquals("Daily report", MessagePreview.title(body));
        assertEquals("all good", MessagePreview.preview(body));
    }

    @Test
    void postSpansThatCarryNoTextAreSkipped() {
        JsonNode body = parse("{\"msg_type\":\"post\",\"content\":{\"post\":{\"zh_cn\":{\"content\":["
                + "[{\"tag\":\"at\",\"user_id\":\"ou_1\",\"user_name\":\"张三\"},"
                + "{\"tag\":\"a\",\"text\":\"详情\",\"href\":\"https://example.com\"},"
                + "{\"tag\":\"img\",\"image_key\":\"img_1\"}]]}}}}");

        assertNull(MessagePreview.title(body), "this post has no title");
        assertEquals("张三详情", MessagePreview.preview(body));
    }

    @Test
    void plainTextMessageHasNoTitle() {
        JsonNode body = parse("{\"msg_type\":\"text\",\"content\":{\"text\":\"hello there\"}}");

        assertNull(MessagePreview.title(body));
        assertEquals("hello there", MessagePreview.preview(body));
    }

    @Test
    void interactiveCardTitleComesFromTheHeader() {
        JsonNode body = parse("{\"msg_type\":\"interactive\",\"card\":{\"header\":{\"title\":"
                + "{\"tag\":\"plain_text\",\"content\":\"构建失败\"}},\"elements\":"
                + "[{\"tag\":\"div\",\"text\":{\"tag\":\"lark_md\",\"content\":\"分支 main\"}}]}}");

        assertEquals("构建失败", MessagePreview.title(body));
        assertTrue(MessagePreview.preview(body).contains("分支 main"));
    }

    @Test
    void imageMessagePreviewsItsKey() {
        JsonNode body = parse("{\"msg_type\":\"image\",\"content\":{\"image_key\":\"img_v3_abc\"}}");

        assertNull(MessagePreview.title(body));
        assertEquals("img_v3_abc", MessagePreview.preview(body));
    }

    @Test
    void unknownTypeFallsBackToWhateverTextItCanFind() {
        JsonNode body = parse("{\"msg_type\":\"something_new\",\"payload\":{\"nested\":"
                + "{\"text\":\"still readable\"}}}");

        assertEquals("still readable", MessagePreview.preview(body));
    }

    @Test
    void toleratesNullAndNonObjectInput() {
        assertNull(MessagePreview.title(null));
        assertNull(MessagePreview.preview(null));
        assertNull(MessagePreview.title(parse("[1,2,3]")));
        assertNull(MessagePreview.preview(parse("\"just a string\"")));
    }

    @Test
    void truncatesByCodePointSoEmojiAreNeverSplit() {
        StringBuilder emoji = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            emoji.append("🎮");           // 一个码点，两个字符
        }
        JsonNode body = parse("{\"msg_type\":\"text\",\"content\":{\"text\":\"" + emoji + "\"}}");

        String preview = MessagePreview.preview(body);

        assertEquals(200, preview.codePointCount(0, preview.length()));
        assertEquals(400, preview.length(), "200 code points of emoji is 400 chars");
        assertFalse(Character.isHighSurrogate(preview.charAt(preview.length() - 1)),
                "a lone high surrogate means the last emoji was cut in half");
    }
}
