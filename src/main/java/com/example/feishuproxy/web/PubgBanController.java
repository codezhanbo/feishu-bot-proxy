package com.example.feishuproxy.web;

import com.example.feishuproxy.core.PubgBanClient;
import com.example.feishuproxy.model.BanCheckResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后台「封禁查询」页的查询入口，路径挂在 {@code /console/**} 下，走会话鉴权。
 * <p>
 * 无论上游查到与否，这里都回 200——「查无此人」是正常业务结果，不该用 HTTP 状态码表达；
 * 只有玩家名缺失才报 400。查询失败的原因放在响应体的 {@code error} 字段里。
 */
@RestController
public class PubgBanController {

    private final PubgBanClient client;
    private final ObjectMapper objectMapper;

    public PubgBanController(PubgBanClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/console/ban-check")
    public ResponseEntity<String> banCheck(@RequestParam("player") String player,
                                           @RequestParam(value = "platform", defaultValue = "steam") String platform) {
        String name = player == null ? "" : player.trim();
        if (name.isEmpty()) {
            return JsonResponses.error(objectMapper, 400, 40001, "player is required");
        }

        String platformKey = platform == null || platform.trim().isEmpty() ? "steam" : platform.trim();
        BanCheckResult result = client.check(name, platformKey);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", result.isSuccess());
        if (result.isSuccess()) {
            out.put("playerName", result.getPlayerName());
            out.put("banStatus", result.getBanStatus());
            out.put("banType", result.getBanType());
            out.put("matchCount", result.getMatchCount());
            out.put("siteUUID", result.getSiteUUID());
        } else {
            out.put("error", result.getError());
        }
        return JsonResponses.ok(objectMapper, out);
    }
}
