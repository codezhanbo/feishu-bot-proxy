package com.example.feishuproxy.core;

import com.example.feishuproxy.model.BanCheckResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 调 pubg.hk 的封禁查询接口，负责拼 URL、加签（见 {@link PubgApiSignature}）、发请求与解析。
 * <p>
 * 用专为 pubg.hk 配的 {@code pubgRestTemplate} Bean：读超时放宽到 60s，且对 4xx/5xx 不抛异常，
 * 这样上游返回的「查无此人」类报文能原样读到并解析，而不是在半路被当成传输错误丢掉。
 */
@Component
public class PubgBanClient {

    private static final Logger log = LoggerFactory.getLogger(PubgBanClient.class);

    private static final String BASE_URL = "https://pubg.hk";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PubgBanClient(@Qualifier("pubgRestTemplate") RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询一个玩家的封禁状态。
     *
     * @param player   玩家昵称，原样传给 pubg.hk（不做前缀处理）
     * @param platform 平台：steam / kakao / console / xbox / psn
     */
    public BanCheckResult check(String player, String platform) {
        String url = signedUrl(player, platform);
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode body = objectMapper.readTree(response.getBody());
            return BanCheckResult.from(body);
        } catch (RestClientException e) {
            log.warn("pubg.hk ban-check unreachable player={} platform={}: {}",
                    player, platform, e.getMessage());
            return BanCheckResult.failure("pubg.hk 请求失败：" + e.getMessage());
        } catch (IOException e) {
            log.warn("pubg.hk ban-check bad payload player={} platform={}", player, platform, e);
            return BanCheckResult.failure("pubg.hk 响应解析失败");
        }
    }

    /** 拼出带签名的完整 URL。query 值先做 URL 编码，加签时再由 {@code signUrl} 解码回原值。 */
    private String signedUrl(String player, String platform) {
        String query = "/api/ban/check?player=" + encode(player) + "&platform=" + encode(platform);
        return PubgApiSignature.signUrl(BASE_URL + query);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            // UTF-8 是 JDK 必定提供的字符集，这里不会走到。
            throw new IllegalStateException("UTF-8 不可用", e);
        }
    }
}
