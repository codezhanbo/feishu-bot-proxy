package com.example.feishuproxy.web;

import com.example.feishuproxy.model.AlertRule;
import com.example.feishuproxy.model.AlertRunLog;
import com.example.feishuproxy.store.AlertRuleRepository;
import com.example.feishuproxy.store.AlertRunLogRepository;
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
 * 告警规则的后台接口（{@code /console/alerts}），走 {@link AdminSessionInterceptor} 会话鉴权，
 * 与机器人配置（{@link AdminConsoleController}）并列。规则字段见 {@link AlertRule}。
 */
@RestController
public class AlertConsoleController {

    private final AlertRuleRepository repository;
    private final AlertRunLogRepository runLog;
    private final ObjectMapper objectMapper;

    public AlertConsoleController(AlertRuleRepository repository, AlertRunLogRepository runLog,
                                  ObjectMapper objectMapper) {
        this.repository = repository;
        this.runLog = runLog;
        this.objectMapper = objectMapper;
    }

    /** 全部规则，按 id 升序。数据库不可用时返回 503。 */
    @GetMapping("/console/alerts")
    public ResponseEntity<String> list() {
        List<AlertRule> rules = repository.findAll();
        if (rules == null) {
            return JsonResponses.error(objectMapper, 503, 50301, "alert rule store unavailable");
        }
        ArrayNode items = objectMapper.createArrayNode();
        for (AlertRule rule : rules) {
            items.addObject()
                    .put("id", rule.getId())
                    .put("botKey", rule.getBotKey())
                    .put("thresholdMinutes", rule.getThresholdMinutes())
                    .put("cooldownMinutes", rule.getCooldownMinutes())
                    .put("enabled", rule.isEnabled())
                    .put("alertBotKey", rule.getAlertBotKey())
                    .put("lastAlertAt", rule.getLastAlertAt() == null ? null : rule.getLastAlertAt().longValue())
                    .put("createdAt", rule.getCreatedAt())
                    .put("updatedAt", rule.getUpdatedAt());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rules", items);
        return JsonResponses.ok(objectMapper, out);
    }

    /** 调度执行日志，最新在前。数据库不可用时返回 503。 */
    @GetMapping("/console/alert-runs")
    public ResponseEntity<String> alertRuns(@RequestParam(defaultValue = "50") int limit,
                                            @RequestParam(defaultValue = "0") int offset) {
        List<AlertRunLog> runs = runLog.query(limit, offset);
        if (runs == null) {
            return JsonResponses.error(objectMapper, 503, 50301, "alert run log store unavailable");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", runs.size());
        out.put("total", runLog.total());
        out.put("offset", Math.max(0, offset));
        out.put("maxLimit", AlertRunLogRepository.MAX_PAGE);
        out.put("records", runs);
        return JsonResponses.ok(objectMapper, out);
    }

    @PostMapping("/console/alerts")
    public ResponseEntity<String> create(@RequestBody JsonNode body) {
        ResponseEntity<String> invalid = validate(body);
        if (invalid != null) {
            return invalid;
        }
        AlertRule rule = build(body, new AlertRule());
        try {
            long id = repository.insert(rule);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            return JsonResponses.ok(objectMapper, out);
        } catch (IllegalStateException e) {
            return JsonResponses.error(objectMapper, 503, 50301, "alert rule store unavailable");
        }
    }

    @PutMapping("/console/alerts/{id}")
    public ResponseEntity<String> update(@PathVariable("id") long id, @RequestBody JsonNode body) {
        ResponseEntity<String> invalid = validate(body);
        if (invalid != null) {
            return invalid;
        }
        try {
            AlertRule existing = repository.find(id);
            if (existing == null) {
                return JsonResponses.error(objectMapper, 404, 40401, "unknown alert rule: " + id);
            }
            AlertRule rule = build(body, existing);
            rule.setId(id);
            repository.update(rule);
        } catch (IllegalStateException e) {
            return JsonResponses.error(objectMapper, 503, 50301, "alert rule store unavailable");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        return JsonResponses.ok(objectMapper, out);
    }

    @DeleteMapping("/console/alerts/{id}")
    public ResponseEntity<String> delete(@PathVariable("id") long id) {
        try {
            if (repository.find(id) == null) {
                return JsonResponses.error(objectMapper, 404, 40401, "unknown alert rule: " + id);
            }
            repository.delete(id);
        } catch (IllegalStateException e) {
            return JsonResponses.error(objectMapper, 503, 50301, "alert rule store unavailable");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        return JsonResponses.ok(objectMapper, out);
    }

    /** 校验失败时返回错误响应，否则返回 null。 */
    private ResponseEntity<String> validate(JsonNode body) {
        String botKey = body.path("botKey").asText("");
        if (botKey.trim().isEmpty()) {
            return JsonResponses.error(objectMapper, 400, 40002, "botKey is required");
        }
        if (botKey.indexOf(',') >= 0) {
            return JsonResponses.error(objectMapper, 400, 40002, "botKey must not contain ',': " + botKey);
        }
        String alertBotKey = body.path("alertBotKey").asText("");
        if (alertBotKey.trim().isEmpty()) {
            return JsonResponses.error(objectMapper, 400, 40002, "alertBotKey is required");
        }
        if (alertBotKey.indexOf(',') >= 0) {
            return JsonResponses.error(objectMapper, 400, 40002, "alertBotKey must not contain ',': " + alertBotKey);
        }
        int threshold = body.path("thresholdMinutes").asInt(0);
        if (threshold < 1) {
            return JsonResponses.error(objectMapper, 400, 40003, "thresholdMinutes must be >= 1");
        }
        int cooldown = body.path("cooldownMinutes").asInt(0);
        if (cooldown < 1) {
            return JsonResponses.error(objectMapper, 400, 40003, "cooldownMinutes must be >= 1");
        }
        return null;
    }

    private static AlertRule build(JsonNode body, AlertRule rule) {
        rule.setBotKey(body.path("botKey").asText("").trim());
        rule.setAlertBotKey(body.path("alertBotKey").asText("").trim());
        rule.setThresholdMinutes(body.path("thresholdMinutes").asInt(rule.getThresholdMinutes()));
        rule.setCooldownMinutes(body.path("cooldownMinutes").asInt(rule.getCooldownMinutes()));
        rule.setEnabled(body.has("enabled") ? body.path("enabled").asBoolean(true) : rule.isEnabled());
        return rule;
    }
}
