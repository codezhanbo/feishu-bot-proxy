package com.example.feishuproxy.web;

import com.example.feishuproxy.core.PubgBanClient;
import com.example.feishuproxy.model.Account;
import com.example.feishuproxy.model.BanCheckLog;
import com.example.feishuproxy.model.BanCheckResult;
import com.example.feishuproxy.store.AccountRepository;
import com.example.feishuproxy.store.BanCheckLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
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
    private final BanCheckLogRepository banCheckLog;
    private final AccountRepository accounts;
    private final ObjectMapper objectMapper;

    public PubgBanController(PubgBanClient client, BanCheckLogRepository banCheckLog,
                             AccountRepository accounts, ObjectMapper objectMapper) {
        this.client = client;
        this.banCheckLog = banCheckLog;
        this.accounts = accounts;
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

        // 每次查询都留档，成功失败都写；这两个旁路都不能影响上面的查询应答。
        banCheckLog.record(name, platformKey, result);
        if (result.isSuccess()) {
            accounts.updateFromCheck(name, toBanStatus(result),
                    result.getLevelText(),
                    result.getTotalMatches() == null ? null : result.getTotalMatches().longValue(),
                    BanCheckLog.format(System.currentTimeMillis()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", result.isSuccess());
        if (result.isSuccess()) {
            out.put("playerName", result.getPlayerName());
            out.put("platform", result.getPlatform());
            out.put("banStatus", result.getBanStatus());
            out.put("banType", result.getBanType());
            out.put("matchCount", result.getMatchCount());
            out.put("level", result.getLevelText());
            out.put("totalMatches", result.getTotalMatches());
            out.put("siteUUID", result.getSiteUUID());
        } else {
            out.put("error", result.getError());
        }
        return JsonResponses.ok(objectMapper, out);
    }

    /**
     * 批量查询所有账号的封禁状态与等级并回填。逐个串行调用上游，避免并发打爆 pubg.hk；
     * 单个账号失败不影响其余账号。返回每个账号的结果汇总。
     */
    @PostMapping("/console/accounts/batch-check")
    public ResponseEntity<String> batchCheck() {
        List<Account> all = accounts.findAll();
        if (all == null) {
            return JsonResponses.error(objectMapper, 503, 50301, "account store unavailable");
        }
        ArrayNode results = objectMapper.createArrayNode();
        int success = 0;
        int failed = 0;
        for (Account account : all) {
            String name = account.getAccountId();
            String platformKey = account.getPlatform() == null || account.getPlatform().trim().isEmpty()
                    ? "steam" : account.getPlatform().trim();
            BanCheckResult result = client.check(name, platformKey);
            banCheckLog.record(name, platformKey, result);

            ObjectNode item = results.addObject();
            item.put("accountId", name);
            if (result.isSuccess()) {
                accounts.updateFromCheck(name, toBanStatus(result), result.getLevelText(),
                        result.getTotalMatches() == null ? null : result.getTotalMatches().longValue(),
                        BanCheckLog.format(System.currentTimeMillis()));
                item.put("success", true);
                item.put("banStatus", result.getBanStatus());
                item.put("level", result.getLevelText());
                if (result.getTotalMatches() != null) {
                    item.put("totalMatches", result.getTotalMatches().longValue());
                }
                success++;
            } else {
                item.put("success", false);
                item.put("error", result.getError());
                failed++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("success", success);
        out.put("failed", failed);
        out.put("results", results);
        return JsonResponses.ok(objectMapper, out);
    }

    /**
     * 把上游结果归一成账号表的封禁状态。逻辑收敛在 {@link AccountRepository#toBanStatus(BanCheckResult)}，
     * 这里保留一个同名的包内入口，供本控制器与既有测试直接调用。
     */
    static String toBanStatus(BanCheckResult result) {
        return AccountRepository.toBanStatus(result);
    }
}
