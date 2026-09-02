package com.example.feishuproxy.core;

import com.example.feishuproxy.config.FeishuProperties;
import com.example.feishuproxy.config.HttpClientConfig;
import com.example.feishuproxy.model.SendResult;
import com.example.feishuproxy.store.StatsCollector;
import com.example.feishuproxy.support.MockFeishuServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeishuSenderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static MockFeishuServer mock;

    @BeforeAll
    static void startMock() throws IOException {
        mock = new MockFeishuServer();
    }

    @AfterAll
    static void stopMock() {
        mock.close();
    }

    @AfterEach
    void resetMock() {
        mock.reset();
    }

    @Test
    void forwardsCallerBytesUntouchedWhenBotHasNoSecret() throws Exception {
        // 刻意写成不规整的空白和键顺序：如果转发环节重新序列化，回传的内容就会被规范化，
        // 字节比对就会失败。
        byte[] body = "{ \"msg_type\" : \"text\" ,  \"content\" : { \"text\" : \"构建失败 ✅\" } }"
                .getBytes(StandardCharsets.UTF_8);

        SendResult result = send(bot(mock.url(), ""), body);

        assertTrue(result.isSuccess());
        assertArrayEquals(body, mock.lastRequestBytes(), "bytes must reach Feishu exactly as sent");
        assertTrue(mock.lastRequestBody().contains("构建失败 ✅"), "UTF-8 must survive the hop");
    }

    @Test
    void injectsTimestampAndSignWhenSecretIsConfigured() throws Exception {
        byte[] body = "{\"msg_type\":\"text\",\"content\":{\"text\":\"hello\"}}"
                .getBytes(StandardCharsets.UTF_8);

        SendResult result = send(bot(mock.url(), "my-secret-key"), body);

        assertTrue(result.isSuccess());
        JsonNode sent = MAPPER.readTree(mock.lastRequestBody());
        long timestamp = Long.parseLong(sent.path("timestamp").asText());
        assertEquals(SignatureUtil.sign(timestamp, "my-secret-key"), sent.path("sign").asText());
        assertEquals("text", sent.path("msg_type").asText(), "original payload must be preserved");
        assertEquals("hello", sent.path("content").path("text").asText());
    }

    @Test
    void signingLeavesTheCallersParsedBodyAlone() throws Exception {
        // Controller 在发送后仍会继续读取这个节点，以生成待存储的记录。
        // timestamp/sign 这一对字段绝不能泄漏进去。
        byte[] body = "{\"msg_type\":\"text\",\"content\":{\"text\":\"hi\"}}"
                .getBytes(StandardCharsets.UTF_8);
        JsonNode shared = MAPPER.readTree(body);
        FeishuSender sender = sender(defaults());

        sender.send("a", bot(mock.url(), "secret-a"), body, shared, "text", "127.0.0.1");
        String firstSent = mock.lastRequestBody();
        sender.send("b", bot(mock.url(), "secret-b"), body, shared, "text", "127.0.0.1");
        String secondSent = mock.lastRequestBody();

        assertFalse(shared.has("sign"), "the caller's node must not be polluted");
        JsonNode first = MAPPER.readTree(firstSent);
        JsonNode second = MAPPER.readTree(secondSent);
        assertEquals(SignatureUtil.sign(Long.parseLong(first.path("timestamp").asText()), "secret-a"),
                first.path("sign").asText());
        assertEquals(SignatureUtil.sign(Long.parseLong(second.path("timestamp").asText()), "secret-b"),
                second.path("sign").asText());
    }

    @Test
    void retriesOnCode9499AndSucceedsOnTheSecondAttempt() throws Exception {
        mock.enqueue(200, "{\"code\":9499,\"msg\":\"too many request\"}");

        SendResult result = send(bot(mock.url(), ""), textBody());

        assertTrue(result.isSuccess());
        assertEquals(0, result.getCode());
        assertEquals(2, result.getAttempts());
        assertEquals(2, mock.requestCount());
    }

    @Test
    void retriesOnCode11232TheTenantWideFrequencyLimit() throws Exception {
        // 真实报文：11232 是租户级限流，整点高发，本地限流器拦不住。
        // 不重试就等于整点那条战报直接丢掉。
        mock.enqueue(200, "{\"code\":11232,\"msg\":\"frequency limited "
                + "psm[lark.oapi.app_platform_runtime]appID[1500]\"}");

        SendResult result = send(bot(mock.url(), ""), textBody());

        assertTrue(result.isSuccess(), "退避之后第二次应当发得出去");
        assertEquals(2, result.getAttempts());
        assertEquals(2, mock.requestCount());
    }

    @Test
    void doesNotRetryOnCode19021AndPassesTheBodyBack() throws Exception {
        mock.setFallback(200, "{\"code\":19021,\"msg\":\"sign match fail\"}");

        SendResult result = send(bot(mock.url(), ""), textBody());

        assertFalse(result.isSuccess());
        assertEquals(19021, result.getCode());
        assertEquals(1, result.getAttempts());
        assertEquals(1, mock.requestCount(), "a bad signature would fail identically on a retry");
        assertTrue(result.isPassthrough());
        assertTrue(result.getFeishuBody().contains("sign match fail"));
    }

    @Test
    void retriesServerErrorsUpToMaxAttempts() throws Exception {
        mock.setFallback(500, "boom");

        SendResult result = send(bot(mock.url(), ""), textBody());

        assertFalse(result.isSuccess());
        assertEquals(500, result.getHttpStatus());
        assertEquals(3, result.getAttempts());
        assertEquals(3, mock.requestCount());
    }

    @Test
    void reportsUpstreamErrorWhenFeishuIsUnreachable() throws Exception {
        // 回环地址上的 1 端口会拒绝连接，这正是我们想要的传输层故障。
        SendResult result = send(bot("http://127.0.0.1:1/hook", ""), textBody());

        assertFalse(result.isSuccess());
        assertEquals(502, result.getHttpStatus());
        assertEquals(50200, result.getCode());
        assertFalse(result.isPassthrough());
        assertEquals(0, mock.requestCount());
    }

    @Test
    void rejectsLocallyWhenTheRateLimitIsExhausted() throws Exception {
        FeishuProperties properties = defaults();
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setPerMinute(1);
        properties.getRateLimit().setPerSecond(1);
        properties.getRateLimit().setWaitTimeoutMs(0);
        FeishuSender sender = sender(properties);

        byte[] body = textBody();
        JsonNode parsed = MAPPER.readTree(body);
        FeishuProperties.Bot bot = bot(mock.url(), "");

        SendResult first = sender.send("k", bot, body, parsed, "text", "127.0.0.1");
        SendResult second = sender.send("k", bot, body, parsed, "text", "127.0.0.1");

        assertTrue(first.isSuccess());
        assertFalse(second.isSuccess());
        assertEquals(429, second.getHttpStatus());
        assertEquals(42900, second.getCode());
        assertEquals(1, mock.requestCount(), "the throttled call must never reach Feishu");
    }

    @Test
    void failsFastWhenTheBotHasNoWebhookConfigured() throws Exception {
        SendResult result = send(bot("  ", ""), textBody());

        assertFalse(result.isSuccess());
        assertEquals(500, result.getHttpStatus());
        assertEquals(50001, result.getCode());
        assertEquals(0, mock.requestCount());
    }

    private SendResult send(FeishuProperties.Bot bot, byte[] body) throws IOException {
        return sender(defaults()).send("test-bot", bot, body, MAPPER.readTree(body), "text", "127.0.0.1");
    }

    private static byte[] textBody() {
        return "{\"msg_type\":\"text\",\"content\":{\"text\":\"hi\"}}".getBytes(StandardCharsets.UTF_8);
    }

    private static FeishuProperties defaults() {
        FeishuProperties properties = new FeishuProperties();
        properties.getRateLimit().setEnabled(false);
        properties.getRetry().setInitialBackoffMs(5);
        properties.getRetry().setMaxBackoffMs(20);
        properties.setConnectTimeoutMs(1000);
        properties.setReadTimeoutMs(2000);
        return properties;
    }

    private static FeishuProperties.Bot bot(String webhook, String secret) {
        FeishuProperties.Bot bot = new FeishuProperties.Bot();
        bot.setWebhook(webhook);
        bot.setSecret(secret);
        bot.setKeywords(Arrays.asList());
        return bot;
    }

    private static FeishuSender sender(FeishuProperties properties) {
        RestTemplate restTemplate = new HttpClientConfig()
                .feishuRestTemplate(new RestTemplateBuilder(), properties);
        return new FeishuSender(restTemplate, properties, new RetryPolicy(properties),
                new RateLimiterRegistry(properties), new StatsCollector(), MAPPER);
    }
}
