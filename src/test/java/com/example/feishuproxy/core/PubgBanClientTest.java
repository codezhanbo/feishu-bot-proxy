package com.example.feishuproxy.core;

import com.example.feishuproxy.model.BanCheckResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PubgBanClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void signsUrlAndParsesData() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(
                        "{\"success\":true,\"data\":{\"siteUUID\":\"u-1\",\"playerName\":\"CN_A1xleft\","
                                + "\"banStatus\":\"未封禁\",\"banType\":\"Innocent\",\"matchCount\":1078}}",
                        HttpStatus.OK));

        PubgBanClient client = new PubgBanClient(restTemplate, MAPPER);
        BanCheckResult result = client.check("CN_A1xleft", "steam");

        assertTrue(result.isSuccess());
        assertEquals("CN_A1xleft", result.getPlayerName());
        assertEquals("未封禁", result.getBanStatus());
        assertEquals("Innocent", result.getBanType());
        assertEquals(Integer.valueOf(1078), result.getMatchCount());
        assertEquals("u-1", result.getSiteUUID());

        // 捕获真实请求 URL，确认它带上了 ts/sign，且 sign 与同一组参数独立复算一致。
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).getForEntity(urlCaptor.capture(), eq(String.class));
        String url = urlCaptor.getValue();

        assertTrue(url.startsWith("https://pubg.hk/api/ban/check?player=CN_A1xleft&platform=steam&ts="),
                "unexpected url: " + url);
        long ts = Long.parseLong(param(url, "ts"));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("player", "CN_A1xleft");
        params.put("platform", "steam");
        assertEquals(PubgApiSignature.sign(params, ts), param(url, "sign"));
    }

    @Test
    void reportsUpstreamFailureAsNonSuccess() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\":false,\"error\":\"玩家不存在\"}", HttpStatus.OK));

        PubgBanClient client = new PubgBanClient(restTemplate, MAPPER);
        BanCheckResult result = client.check("no-such-player", "steam");

        assertFalse(result.isSuccess());
        assertEquals("玩家不存在", result.getError());
    }

    @Test
    void wrapsNetworkFailure() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenThrow(new RestClientException("connection refused"));

        PubgBanClient client = new PubgBanClient(restTemplate, MAPPER);
        BanCheckResult result = client.check("CN_A1xleft", "steam");

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("connection refused"));
    }

    private static String param(String url, String key) {
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }
}
