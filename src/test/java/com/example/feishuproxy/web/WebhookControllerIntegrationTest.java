package com.example.feishuproxy.web;

import com.example.feishuproxy.support.MockFeishuServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 走真实 HTTP 的端到端测试，用 {@link MockFeishuServer} 顶替 open.feishu.cn。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebhookControllerIntegrationTest {

    private static final MockFeishuServer MOCK = startMock();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static MockFeishuServer startMock() {
        try {
            return new MockFeishuServer();
        } catch (IOException e) {
            throw new IllegalStateException("cannot start mock feishu server", e);
        }
    }

    @DynamicPropertySource
    static void pointBotsAtTheMock(DynamicPropertyRegistry registry) {
        registry.add("feishu.bots.plain.webhook", MOCK::url);
        registry.add("feishu.bots.signed.webhook", MOCK::url);
        registry.add("feishu.bots.second.webhook", MOCK::url);
        registry.add("feishu.bots.paused.webhook", MOCK::url);
        // 存储用 H2 内存库（PostgreSQL 兼容模式）顶替真 Postgres。
        registry.add("feishu.store.jdbc-url",
                () -> "jdbc:h2:mem:it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("feishu.store.username", () -> "sa");
        registry.add("feishu.store.password", () -> "");
    }

    @Autowired
    private TestRestTemplate rest;

    @BeforeEach
    void reset() {
        MOCK.reset();
    }

    @Test
    void singleBotReturnsFeishusOwnBodyVerbatim() {
        ResponseEntity<String> response = post("/webhook/plain", textPayload());

        assertEquals(200, response.getStatusCodeValue());
        assertEquals("{\"code\":0,\"msg\":\"success\"}", response.getBody(),
                "callers that used to hit Feishu directly must see an identical response");
    }

    @Test
    void forwardsTheCallersBytesUnchanged() {
        byte[] body = "{ \"msg_type\" : \"text\" , \"content\" : { \"text\" : \"发布成功 🚀\" } }"
                .getBytes(StandardCharsets.UTF_8);

        post("/webhook/plain", body);

        assertArrayEquals(body, MOCK.lastRequestBytes());
    }

    @Test
    void signsTheBodyWhenTheBotHasASecret() throws Exception {
        post("/webhook/signed", textPayload());

        JsonNode sent = MAPPER.readTree(MOCK.lastRequestBody());
        assertTrue(sent.hasNonNull("timestamp"));
        assertTrue(sent.hasNonNull("sign"));
        assertEquals("text", sent.path("msg_type").asText());
    }

    @Test
    void routesToTheDefaultBotWhenNoKeyIsGiven() {
        ResponseEntity<String> response = post("/webhook", textPayload());

        assertEquals(200, response.getStatusCodeValue());
        assertEquals(1, MOCK.requestCount());
    }

    @Test
    void aCommaSeparatedPathIsRejectedOutright() throws Exception {
        ResponseEntity<String> response = post("/webhook/plain,second", textPayload());

        assertEquals(400, response.getStatusCodeValue(), "广播已移除，逗号不再有意义");
        JsonNode body = MAPPER.readTree(response.getBody());
        assertEquals(40002, body.path("code").asInt());
        assertEquals(0, MOCK.requestCount(), "nothing may be forwarded");
    }

    @Test
    void aRejectedCommaKeyDoesNotPolluteTheBotKeyFilter() throws Exception {
        long secondBefore = MAPPER.readTree(
                rest.getForObject("/admin/logs?botKey=second&limit=1", String.class)).path("count").asLong();

        post("/webhook/plain,second", textPayload());

        // 关键点先断言：bot_keys 列是逗号拼接的，如果把 "plain,second" 原样存成一行，
        // 这个过滤会把它当成一条发给 second 的消息多捞出来。
        long secondAfter = MAPPER.readTree(
                rest.getForObject("/admin/logs?botKey=second&limit=1", String.class)).path("count").asLong();
        assertEquals(secondBefore, secondAfter, "second 从未收到过这条消息");

        JsonNode record = MAPPER.readTree(rest.getForObject("/admin/logs?limit=1", String.class))
                .path("records").get(0);
        assertEquals(40002, record.path("code").asInt(), "被拒的请求仍然落档");
        assertEquals("", record.path("botKeys").asText(), "不能把 \"plain,second\" 当成两个目标存下来");
    }

    @Test
    void rejectsAnUnknownBotKey() throws Exception {
        ResponseEntity<String> response = post("/webhook/nope", textPayload());

        assertEquals(404, response.getStatusCodeValue());
        assertEquals(40401, MAPPER.readTree(response.getBody()).path("code").asInt());
        assertEquals(0, MOCK.requestCount());
    }

    @Test
    void rejectsADisabledBot() throws Exception {
        ResponseEntity<String> response = post("/webhook/paused", textPayload());

        assertEquals(403, response.getStatusCodeValue());
        assertEquals(40301, MAPPER.readTree(response.getBody()).path("code").asInt());
        assertEquals(0, MOCK.requestCount());
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        ResponseEntity<String> response = post("/webhook/plain", "not json at all".getBytes(StandardCharsets.UTF_8));

        assertEquals(400, response.getStatusCodeValue());
        assertEquals(40001, MAPPER.readTree(response.getBody()).path("code").asInt());
        assertEquals(0, MOCK.requestCount());
    }

    @Test
    void rejectsAnEmptyBody() throws Exception {
        ResponseEntity<String> response = post("/webhook/plain", new byte[0]);

        assertEquals(400, response.getStatusCodeValue());
        assertEquals(40001, MAPPER.readTree(response.getBody()).path("code").asInt());
    }

    @Test
    void rejectsBodiesOverFeishusTwentyKilobyteLimit() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 21000; i++) {
            text.append('x');
        }
        byte[] body = ("{\"msg_type\":\"text\",\"content\":{\"text\":\"" + text + "\"}}")
                .getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> response = post("/webhook/plain", body);

        assertEquals(413, response.getStatusCodeValue());
        assertEquals(41301, MAPPER.readTree(response.getBody()).path("code").asInt());
        assertEquals(0, MOCK.requestCount());
    }

    @Test
    void passesFeishuBusinessErrorsStraightThrough() throws Exception {
        MOCK.setFallback(200, "{\"code\":19024,\"msg\":\"Key Words Not Found\"}");

        ResponseEntity<String> response = post("/webhook/plain", textPayload());

        assertEquals(200, response.getStatusCodeValue());
        assertEquals("{\"code\":19024,\"msg\":\"Key Words Not Found\"}", response.getBody());
        assertEquals(1, MOCK.requestCount(), "19024 is deterministic, so it must not be retried");
    }

    @Test
    void adminEndpointsReportWhatHappened() throws Exception {
        post("/webhook/plain", textPayload());

        JsonNode stats = MAPPER.readTree(rest.getForObject("/admin/stats", String.class));
        assertTrue(stats.path("total").asLong() >= 1);

        JsonNode logs = MAPPER.readTree(rest.getForObject("/admin/logs?botKey=plain&limit=10", String.class));
        assertTrue(logs.path("records").size() >= 1);
        JsonNode record = logs.path("records").get(0);
        assertEquals("plain", record.path("botKeys").asText());
        assertEquals("plain", record.path("results").get(0).path("botKey").asText());

        JsonNode health = MAPPER.readTree(rest.getForObject("/health", String.class));
        assertEquals("UP", health.path("status").asText());
    }

    @Test
    void eachRequestIsStoredAsOneRowKeepingTheBodyVerbatim() throws Exception {
        long before = MAPPER.readTree(rest.getForObject("/admin/logs?limit=1", String.class))
                .path("total").asLong();

        post("/webhook/plain", textPayload());

        JsonNode logs = MAPPER.readTree(rest.getForObject("/admin/logs?limit=1", String.class));
        assertEquals(before + 1, logs.path("total").asLong(), "one request is one row");

        JsonNode record = logs.path("records").get(0);
        assertEquals("plain", record.path("botKeys").asText());
        assertEquals(1, record.path("results").size());
        assertTrue(record.path("success").asBoolean());
        // 报文被原样保留，这正是该存储存在的全部意义。
        assertEquals(MAPPER.readTree(textPayload()), MAPPER.readTree(record.path("body").asText()));
    }

    @Test
    void richPostMessageIsStoredWithAReadableTitleAndPreview() throws Exception {
        byte[] payload = ("{\"msg_type\":\"post\",\"content\":{\"post\":{\"zh_cn\":{"
                + "\"title\":\"微凉Pro游戏数据统计 2026-09-01\","
                + "\"content\":[[{\"tag\":\"text\",\"text\":\"🎮累计对局:0\\n\"}],"
                + "[{\"tag\":\"img\",\"image_key\":\"img_v3_02154\"}]]}}}}").getBytes(StandardCharsets.UTF_8);

        post("/webhook/plain", payload);

        JsonNode record = MAPPER.readTree(rest.getForObject("/admin/logs?limit=1", String.class))
                .path("records").get(0);
        assertEquals("post", record.path("msgType").asText());
        assertEquals("微凉Pro游戏数据统计 2026-09-01", record.path("title").asText());
        assertTrue(record.path("textPreview").asText().startsWith("🎮累计对局:0"),
                "preview was: " + record.path("textPreview").asText());
    }

    @Test
    void rejectedRequestsAreStoredToo() throws Exception {
        long before = MAPPER.readTree(rest.getForObject("/admin/logs?limit=1", String.class))
                .path("total").asLong();

        ResponseEntity<String> response = post("/webhook/plain", "not json at all".getBytes(StandardCharsets.UTF_8));
        assertEquals(400, response.getStatusCodeValue());

        JsonNode logs = MAPPER.readTree(rest.getForObject("/admin/logs?limit=1", String.class));
        assertEquals(before + 1, logs.path("total").asLong(), "a rejected message is still a message");

        JsonNode record = logs.path("records").get(0);
        assertFalse(record.path("success").asBoolean());
        assertEquals(40001, record.path("code").asInt());
        assertEquals("not json at all", record.path("body").asText());
        assertEquals(0, record.path("results").size(), "it never reached a bot");
    }

    @Test
    void adminBotListingMasksWebhookCredentials() throws Exception {
        JsonNode bots = MAPPER.readTree(rest.getForObject("/admin/bots", String.class));

        assertTrue(bots.has("plain"));
        assertTrue(bots.path("signed").path("signed").asBoolean(), "signed bot should report signing on");
        assertFalse(bots.path("plain").path("signed").asBoolean());
        assertTrue(bots.path("plain").path("webhook").asText().contains("****"));
    }

    @Test
    void aStatsReportExposesItsNumbersOnTheAdminApi() throws Exception {
        post("/webhook/plain", reportPayload("2026-09-01", 1000, 400, 5, "01:00:00"));
        post("/webhook/plain", reportPayload("2026-09-02", 1250, 470, 6, "01:30:20"));

        JsonNode stats = MAPPER.readTree(rest.getForObject("/admin/logs?limit=1", String.class))
                .path("records").get(0).path("stats");

        assertEquals("2026-09-02", stats.path("statDate").asText());
        assertEquals(6, stats.path("survivalLevel").asInt(), "等级是原值");
        assertEquals(250, stats.path("bpGained").asInt(), "1250 - 1000");
        assertEquals(70, stats.path("expGained").asInt(), "470 - 400");
        assertEquals("00:30:20", stats.path("duration").asText());
        assertFalse(stats.has("empty"), "isEmpty() 是仓储层的内部判断，不该出现在接口里");
    }

    private static byte[] reportPayload(String date, long bp, long exp, int level, String duration) {
        return ("{\"msg_type\":\"post\",\"content\":{\"post\":{\"zh_cn\":{"
                + "\"title\":\"微凉Pro游戏数据统计 " + date + "\",\"content\":[["
                + "{\"tag\":\"text\",\"text\":\"💰累计BP:" + bp + " | 📌生存经验:" + exp + "\\n\"},"
                + "{\"tag\":\"text\",\"text\":\"⭐生存等级:" + level + " | ⏱️耗时:" + duration + "\"}]]}}}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private ResponseEntity<String> post(String path, byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static byte[] textPayload() {
        return "{\"msg_type\":\"text\",\"content\":{\"text\":\"hello\"}}".getBytes(StandardCharsets.UTF_8);
    }
}
