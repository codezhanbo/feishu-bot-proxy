package com.example.feishuproxy.web;

import com.example.feishuproxy.model.Account;
import com.example.feishuproxy.model.BanCheckLog;
import com.example.feishuproxy.store.AccountRepository;
import com.example.feishuproxy.store.BanCheckLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「我的账号」的后台接口（{@code /console/accounts}），走 {@code AdminSessionInterceptor} 会话鉴权，
 * 与告警配置（{@link AlertConsoleController}）并列。账号字段见 {@link Account}。
 * <p>
 * 另附 {@code /console/ban-logs} 供查询封禁查询历史，数据来自 {@link BanCheckLogRepository}。
 */
@RestController
public class AccountConsoleController {

    private final AccountRepository accounts;
    private final BanCheckLogRepository banCheckLog;
    private final ObjectMapper objectMapper;

    public AccountConsoleController(AccountRepository accounts, BanCheckLogRepository banCheckLog,
                                    ObjectMapper objectMapper) {
        this.accounts = accounts;
        this.banCheckLog = banCheckLog;
        this.objectMapper = objectMapper;
    }

    /** 全部账号，按 account_id 升序。数据库不可用时返回 503。 */
    @GetMapping("/console/accounts")
    public ResponseEntity<String> list() {
        List<Account> all = accounts.findAll();
        if (all == null) {
            return JsonResponses.error(objectMapper, 503, 50301, "account store unavailable");
        }
        ArrayNode items = objectMapper.createArrayNode();
        for (Account account : all) {
            items.addObject()
                    .put("accountId", account.getAccountId())
                    .put("banStatus", account.getBanStatus())
                    .put("level", account.getLevel())
                    .put("platform", account.getPlatform())
                    .put("lastCheckedAt", account.getLastCheckedAt())
                    .put("totalMatches", account.getTotalMatches() == null ? null
                            : account.getTotalMatches().longValue());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accounts", items);
        return JsonResponses.ok(objectMapper, out);
    }

    /** 封禁查询历史，最新在前。可按玩家 ID 模糊 / 查询时间区间（from/to，epoch 毫秒）过滤。数据库不可用时返回 503。 */
    @GetMapping("/console/ban-logs")
    public ResponseEntity<String> banLogs(@RequestParam(required = false) String player,
                                          @RequestParam(required = false) Long from,
                                          @RequestParam(required = false) Long to,
                                          @RequestParam(defaultValue = "20") int limit,
                                          @RequestParam(defaultValue = "0") int offset) {
        List<BanCheckLog> logs = banCheckLog.query(player, from, to, limit, offset);
        if (logs == null) {
            return JsonResponses.error(objectMapper, 503, 50301, "ban check log store unavailable");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", logs.size());
        out.put("total", banCheckLog.count(player, from, to));
        out.put("offset", Math.max(0, offset));
        out.put("maxLimit", BanCheckLogRepository.MAX_PAGE);
        out.put("records", logs);
        return JsonResponses.ok(objectMapper, out);
    }

    /** 新增账号：只需 accountId（必填）与 level（可选），封禁状态默认「正常」。 */
    @PostMapping("/console/accounts")
    public ResponseEntity<String> create(@RequestBody JsonNode body) {
        String accountId = normalizeAccountId(body.path("accountId").asText(""));
        if (accountId.isEmpty()) {
            return JsonResponses.error(objectMapper, 400, 40002, "accountId is required");
        }
        String level = emptyToNull(body.path("level").asText(""));
        String platform = normalizePlatform(body.path("platform").asText(""));
        try {
            if (accounts.find(accountId) != null) {
                return JsonResponses.error(objectMapper, 409, 40901, "account already exists: " + accountId);
            }
            Account account = new Account();
            account.setAccountId(accountId);
            account.setBanStatus(AccountRepository.NORMAL);
            account.setLevel(level);
            account.setPlatform(platform);
            accounts.insert(account);
        } catch (IllegalStateException e) {
            return JsonResponses.error(objectMapper, 503, 50301, "account store unavailable");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", accountId);
        return JsonResponses.ok(objectMapper, out);
    }

    /** 更新账号的等级（唯一手工字段）。封禁状态 / 查询时间 / 总场次由封禁查询自动维护。 */
    @PutMapping("/console/accounts/{accountId}")
    public ResponseEntity<String> update(@PathVariable("accountId") String accountId,
                                         @RequestBody JsonNode body) {
        accountId = normalizeAccountId(accountId);
        String level = emptyToNull(body.path("level").asText(""));
        try {
            Account existing = accounts.find(accountId);
            if (existing == null) {
                return JsonResponses.error(objectMapper, 404, 40401, "unknown account: " + accountId);
            }
            // 没传 platform 就保留原值，避免仅改等级时把平台重置。
            String platform = body.has("platform")
                    ? normalizePlatform(body.path("platform").asText(""))
                    : (existing.getPlatform() == null ? "steam" : existing.getPlatform());
            accounts.updateProfile(accountId, level, platform);
        } catch (IllegalStateException e) {
            return JsonResponses.error(objectMapper, 503, 50301, "account store unavailable");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", accountId);
        return JsonResponses.ok(objectMapper, out);
    }

    @DeleteMapping("/console/accounts/{accountId}")
    public ResponseEntity<String> delete(@PathVariable("accountId") String accountId) {
        accountId = normalizeAccountId(accountId);
        try {
            if (accounts.find(accountId) == null) {
                return JsonResponses.error(objectMapper, 404, 40401, "unknown account: " + accountId);
            }
            accounts.delete(accountId);
        } catch (IllegalStateException e) {
            return JsonResponses.error(objectMapper, 503, 50301, "account store unavailable");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", accountId);
        return JsonResponses.ok(objectMapper, out);
    }

    private static String normalizeAccountId(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static String emptyToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 平台归一化：trim，空串回退 steam。 */
    private static String normalizePlatform(String raw) {
        if (raw == null) {
            return "steam";
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? "steam" : trimmed;
    }
}