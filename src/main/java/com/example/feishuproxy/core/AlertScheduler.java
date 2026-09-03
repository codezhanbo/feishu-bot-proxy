package com.example.feishuproxy.core;

import com.example.feishuproxy.config.FeishuProperties;
import com.example.feishuproxy.model.AlertRule;
import com.example.feishuproxy.model.AlertRunLog;
import com.example.feishuproxy.model.SendResult;
import com.example.feishuproxy.store.AlertRuleRepository;
import com.example.feishuproxy.store.AlertRunLogRepository;
import com.example.feishuproxy.store.MessageLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 存活告警调度器：按固定周期检查每条启用的规则，看被监控的 bot 是否已超过阈值时长没有新消息，
 * 超时则向规则指定的机器人发告警。
 * <p>
 * 触发语义（用户确认）：
 * <ul>
 *   <li>从未发过任何消息的 bot 不告警——没有历史，无从判断是「掉线」还是「还没开始用」。</li>
 *   <li>带冷却重复：首次超时立刻发，之后在 {@code cooldownMinutes} 内静默，冷却过了再发；
 *       一旦恢复（有新消息），重置 {@code last_alert_at}，下次超时重新立刻发。</li>
 * </ul>
 * 冷却/去重状态存在 {@code alert_rule.last_alert_at} 列里，重启后依然正确。
 * <p>
 * 每轮检查都往 {@code alert_run_log} 追加一条执行日志（扫描数、告警数、耗时、触发明细），
 * 供后台「调度日志」页回溯。
 */
@Component
public class AlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(AlertScheduler.class);

    /** 告警留档的 clientIp。没有真实 HTTP 请求，用一个可辨识的占位串。 */
    private static final String CLIENT_IP = "alert-scheduler";

    private final AlertRuleRepository rules;
    private final MessageLogRepository messageLog;
    private final BotRegistry registry;
    private final FeishuSender sender;
    private final AlertRunLogRepository runLog;
    private final ObjectMapper objectMapper;

    public AlertScheduler(AlertRuleRepository rules, MessageLogRepository messageLog,
                          BotRegistry registry, FeishuSender sender, AlertRunLogRepository runLog,
                          ObjectMapper objectMapper) {
        this.rules = rules;
        this.messageLog = messageLog;
        this.registry = registry;
        this.sender = sender;
        this.runLog = runLog;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${feishu.alert.check-interval-ms:600000}")
    public void check() {
        long startedAt = System.currentTimeMillis();
        List<AlertRule> list = rules.findAll();
        if (list == null) {
            // 库不可用：跳过本轮，下轮再试。执行日志也落不进去（同一个库），记 skipped。
            recordRun(startedAt, "skipped", 0, 0, null);
            return;
        }

        long now = System.currentTimeMillis();
        int alertsFired = 0;
        ArrayNode detail = objectMapper.createArrayNode();
        for (AlertRule rule : list) {
            try {
                Long idle = checkRule(rule, now);
                if (idle != null) {
                    alertsFired++;
                    detail.addObject()
                            .put("ruleId", rule.getId())
                            .put("botKey", rule.getBotKey())
                            .put("alertBotKey", rule.getAlertBotKey())
                            .put("idleMinutes", idle);
                }
            } catch (RuntimeException e) {
                // 单条规则出错（如中途断连）不影响其余规则，也不终止本轮。
                log.warn("alert check failed for rule {}", rule.getId(), e);
            }
        }
        recordRun(startedAt, "ok", list.size(), alertsFired, alertsFired == 0 ? null : detail);
    }

    /** 记录一条执行日志。写日志失败绝不能反过来影响调度（比如库在写日志那一刻刚好断了）。 */
    private void recordRun(long startedAt, String status, int scanned, int fired, ArrayNode detail) {
        AlertRunLog logEntry = new AlertRunLog();
        logEntry.setExecutedAt(startedAt);
        logEntry.setStatus(status);
        logEntry.setRulesScanned(scanned);
        logEntry.setAlertsFired(fired);
        logEntry.setDurationMs(System.currentTimeMillis() - startedAt);
        logEntry.setDetail(detail == null ? null : detail.toString());
        try {
            runLog.insert(logEntry);
        } catch (RuntimeException e) {
            log.warn("failed to persist alert run log", e);
        }
    }

    /** 返回本轮是否触发了告警（触发则返回已空闲分钟数，否则返回 null）。 */
    private Long checkRule(AlertRule rule, long now) {
        if (!rule.isEnabled()) {
            // 停用视为恢复：清掉告警态，下次启用后从零重新计时。
            if (rule.getLastAlertAt() != null) {
                rules.setLastAlertAt(rule.getId(), null);
            }
            return null;
        }

        Long last = messageLog.lastCreatedAt(rule.getBotKey());
        // 从未有过消息记录时不告警。unhealthy = 有历史、且最近一条已超过阈值。
        boolean unhealthy = last != null && (now - last) >= rule.getThresholdMinutes() * 60_000L;
        if (!unhealthy) {
            if (rule.getLastAlertAt() != null) {
                rules.setLastAlertAt(rule.getId(), null);
            }
            return null;
        }

        Long lastAlertAt = rule.getLastAlertAt();
        long cooldownMs = rule.getCooldownMinutes() * 60_000L;
        if (lastAlertAt != null && now - lastAlertAt < cooldownMs) {
            return null; // 冷却期内，静默
        }

        long idleMinutes = (now - last) / 60_000L;
        sendAlert(rule, idleMinutes);
        rules.setLastAlertAt(rule.getId(), now);
        return idleMinutes;
    }

    private void sendAlert(AlertRule rule, long idleMinutes) {
        FeishuProperties.Bot alertBot = registry.get(rule.getAlertBotKey());
        if (alertBot == null) {
            log.warn("alert rule {} targets unknown bot {}", rule.getId(), rule.getAlertBotKey());
            return;
        }
        if (!alertBot.isEnabled()) {
            log.warn("alert rule {} targets disabled bot {}", rule.getId(), rule.getAlertBotKey());
            return;
        }

        String text = "[feishu-bot-proxy] 告警：机器人 " + rule.getBotKey()
                + " 已 " + idleMinutes + " 分钟没有消息记录"
                + "（阈值 " + rule.getThresholdMinutes() + " 分钟）";

        // 若目标 bot 配置了关键词，带上第一个，好让飞书的关键词校验通过（与 /admin/test 一致）。
        List<String> keywords = alertBot.getKeywords();
        String prefix = (keywords == null || keywords.isEmpty()) ? "" : keywords.get(0) + " ";

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("msg_type", "text");
        payload.putObject("content").put("text", prefix + text);

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            log.warn("alert rule {} failed to build payload", rule.getId(), e);
            return;
        }

        SendResult result = sender.send(rule.getAlertBotKey(), alertBot, body, payload, "text", CLIENT_IP);
        // 和其它消息一样留档：告警会出现在 /console/logs，方便事后确认「到底发出去没有」。
        messageLog.record(Collections.singletonList(rule.getAlertBotKey()), body, payload, CLIENT_IP,
                result.getCode(), result.getMsg(), Collections.singletonList(result));

        log.info("alert fired rule={} bot={} alertBot={} code={} idleMinutes={}",
                rule.getId(), rule.getBotKey(), rule.getAlertBotKey(), result.getCode(), idleMinutes);
    }
}
