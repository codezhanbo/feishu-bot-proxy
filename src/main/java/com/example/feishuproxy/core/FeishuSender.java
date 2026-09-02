package com.example.feishuproxy.core;

import com.example.feishuproxy.config.FeishuProperties;
import com.example.feishuproxy.model.FeishuResponse;
import com.example.feishuproxy.model.SendResult;
import com.example.feishuproxy.store.StatsCollector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

/**
 * 向单个机器人发送一次请求：限流、可选加签、POST、重试、留档记录。
 */
@Component
public class FeishuSender {

    private static final Logger log = LoggerFactory.getLogger(FeishuSender.class);

    private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");

    private final RestTemplate restTemplate;
    private final FeishuProperties properties;
    private final RetryPolicy retryPolicy;
    private final RateLimiterRegistry rateLimiters;
    private final StatsCollector stats;
    private final ObjectMapper objectMapper;

    public FeishuSender(RestTemplate feishuRestTemplate,
                        FeishuProperties properties,
                        RetryPolicy retryPolicy,
                        RateLimiterRegistry rateLimiters,
                        StatsCollector stats,
                        ObjectMapper objectMapper) {
        this.restTemplate = feishuRestTemplate;
        this.properties = properties;
        this.retryPolicy = retryPolicy;
        this.rateLimiters = rateLimiters;
        this.stats = stats;
        this.objectMapper = objectMapper;
    }

    /**
     * @param originalBody 调用方的原始字节，当机器人没有加签密钥时原样转发
     * @param parsedBody   同一份请求体解析后的结果，用于加签和读取 msg_type
     */
    public SendResult send(String botKey, FeishuProperties.Bot bot, byte[] originalBody,
                           JsonNode parsedBody, String msgType, String clientIp) {

        if (bot.getWebhook() == null || bot.getWebhook().trim().isEmpty()) {
            SendResult result = SendResult.localError(botKey, 500, 50001, "bot webhook not configured: " + botKey);
            record(result, msgType, clientIp, originalBody.length);
            return result;
        }

        if (properties.getRateLimit().isEnabled()
                && !rateLimiters.get(botKey).tryAcquire(properties.getRateLimit().getWaitTimeoutMs())) {
            stats.recordRejected(botKey);
            SendResult result = SendResult.localError(botKey, 429, 42900, "rate limit exceeded");
            log.warn("rate limited botKey={} msgType={} clientIp={}", botKey, msgType, clientIp);
            return result;
        }

        byte[] payload;
        try {
            payload = buildPayload(bot, originalBody, parsedBody);
        } catch (Exception e) {
            log.error("failed to sign request botKey={}", botKey, e);
            SendResult result = SendResult.localError(botKey, 500, 50002, "sign failed");
            record(result, msgType, clientIp, originalBody.length);
            return result;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(JSON_UTF8);
        HttpEntity<byte[]> entity = new HttpEntity<>(payload, headers);

        long startedAt = System.currentTimeMillis();
        int maxAttempts = retryPolicy.maxAttempts();
        String lastError = "unknown";

        for (int index = 0; index < maxAttempts; index++) {
            int attempts = index + 1;
            boolean lastAttempt = attempts >= maxAttempts;

            try {
                ResponseEntity<byte[]> response =
                        restTemplate.exchange(bot.getWebhook(), HttpMethod.POST, entity, byte[].class);
                int status = response.getStatusCodeValue();
                String body = response.getBody() == null
                        ? "" : new String(response.getBody(), StandardCharsets.UTF_8);
                FeishuResponse parsed = FeishuResponse.parse(objectMapper, body);

                if (lastAttempt || !retryPolicy.isRetryable(false, status, parsed.getCode())) {
                    SendResult result = SendResult.fromFeishu(botKey, status, body, parsed,
                            attempts, System.currentTimeMillis() - startedAt);
                    record(result, msgType, clientIp, originalBody.length);
                    return result;
                }
                lastError = "code=" + parsed.getCode() + " msg=" + parsed.getMsg();
            } catch (ResourceAccessException e) {
                lastError = String.valueOf(e.getMessage());
            } catch (RestClientException e) {
                lastError = String.valueOf(e.getMessage());
            }

            if (lastAttempt) {
                break;
            }
            if (!sleep(retryPolicy.backoffMillis(index))) {
                break;
            }
            log.warn("retrying botKey={} attempt={}/{} lastError={}", botKey, attempts + 1, maxAttempts, lastError);
        }

        SendResult result = SendResult.upstreamError(botKey, lastError, maxAttempts,
                System.currentTimeMillis() - startedAt);
        record(result, msgType, clientIp, originalBody.length);
        return result;
    }

    /**
     * 没有密钥时，调用方的字节原样发出；有密钥时则需要注入签名字段，因此请求体会从一份副本重新序列化。
     * 之所以用副本，是因为控制器之后还要读取解析后的节点来构造落库记录，而 timestamp/sign 这一对
     * 字段不该出现在那份记录里。
     */
    private byte[] buildPayload(FeishuProperties.Bot bot, byte[] originalBody, JsonNode parsedBody)
            throws Exception {
        if (!bot.hasSecret()) {
            return originalBody;
        }
        ObjectNode node = (ObjectNode) parsedBody.deepCopy();
        long timestamp = System.currentTimeMillis() / 1000L;
        node.put("timestamp", String.valueOf(timestamp));
        node.put("sign", SignatureUtil.sign(timestamp, bot.getSecret()));
        return objectMapper.writeValueAsBytes(node);
    }

    private boolean sleep(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void record(SendResult result, String msgType, String clientIp, int bodyBytes) {
        stats.record(result.getBotKey(), result.isSuccess(), result.getCode(), result.getCostMs());

        // 消息本身的持久化记录由控制器在每个请求中写入一次（落到 SQLite），
        // 这里输出的是每次投递的运行轨迹。
        if (result.isSuccess()) {
            log.info("sent botKey={} msgType={} code={} attempts={} costMs={} clientIp={} bodyBytes={}",
                    result.getBotKey(), msgType, result.getCode(), result.getAttempts(),
                    result.getCostMs(), clientIp, bodyBytes);
        } else {
            log.warn("send failed botKey={} msgType={} code={} msg={} attempts={} costMs={} clientIp={} bodyBytes={}",
                    result.getBotKey(), msgType, result.getCode(), result.getMsg(),
                    result.getAttempts(), result.getCostMs(), clientIp, bodyBytes);
        }
    }
}
