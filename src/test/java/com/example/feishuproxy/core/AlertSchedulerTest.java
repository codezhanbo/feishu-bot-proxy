package com.example.feishuproxy.core;

import com.example.feishuproxy.config.FeishuProperties;
import com.example.feishuproxy.model.AlertLog;
import com.example.feishuproxy.model.AlertRule;
import com.example.feishuproxy.model.AlertRunLog;
import com.example.feishuproxy.model.SendResult;
import com.example.feishuproxy.store.AlertLogRepository;
import com.example.feishuproxy.store.AlertRuleRepository;
import com.example.feishuproxy.store.AlertRunLogRepository;
import com.example.feishuproxy.store.MessageLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertSchedulerTest {

    private static final long MINUTE = 60_000L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AlertRuleRepository rules = mock(AlertRuleRepository.class);
    private final MessageLogRepository messageLog = mock(MessageLogRepository.class);
    private final BotRegistry registry = mock(BotRegistry.class);
    private final FeishuSender sender = mock(FeishuSender.class);
    private final AlertRunLogRepository runLog = mock(AlertRunLogRepository.class);
    private final AlertLogRepository alertLog = mock(AlertLogRepository.class);
    private final AlertScheduler scheduler =
            new AlertScheduler(rules, messageLog, registry, sender, runLog, alertLog, mapper);

    private static AlertRule rule(long id, boolean enabled, int threshold, int cooldown, Long lastAlertAt) {
        AlertRule rule = new AlertRule();
        rule.setId(id);
        rule.setBotKey("dev-group");
        rule.setAlertBotKey("ops-group");
        rule.setThresholdMinutes(threshold);
        rule.setCooldownMinutes(cooldown);
        rule.setEnabled(enabled);
        rule.setLastAlertAt(lastAlertAt);
        return rule;
    }

    private static FeishuProperties.Bot alertBot() {
        FeishuProperties.Bot bot = new FeishuProperties.Bot();
        bot.setWebhook("https://open.feishu.cn/open-apis/bot/v2/hook/xxx");
        bot.setEnabled(true);
        return bot;
    }

    @Test
    void firesAlertWhenTheThresholdIsExceeded() {
        AlertRule r = rule(1L, true, 30, 30, null);
        when(rules.findAll()).thenReturn(Collections.singletonList(r));
        when(messageLog.lastCreatedAt("dev-group")).thenReturn(System.currentTimeMillis() - 40 * MINUTE);
        when(registry.get("ops-group")).thenReturn(alertBot());
        when(sender.send(eq("ops-group"), any(FeishuProperties.Bot.class), any(byte[].class),
                any(JsonNode.class), eq("text"), eq("alert-scheduler")))
                .thenReturn(SendResult.localError("ops-group", 200, 0, "ok"));

        scheduler.check();

        verify(sender).send(eq("ops-group"), any(FeishuProperties.Bot.class), any(byte[].class),
                any(JsonNode.class), eq("text"), eq("alert-scheduler"));
        verify(rules).setLastAlertAt(eq(1L), any(Long.class));

        ArgumentCaptor<AlertLog> alertCaptor = ArgumentCaptor.forClass(AlertLog.class);
        verify(alertLog).insert(alertCaptor.capture());
        AlertLog entry = alertCaptor.getValue();
        assertEquals(Long.valueOf(1L), entry.getRuleId());
        assertEquals("dev-group", entry.getBotKey());
        assertEquals("ops-group", entry.getAlertBotKey());
        assertEquals(30, entry.getThresholdMinutes());
        assertEquals(0, entry.getSendCode());
        assertEquals("ok", entry.getSendMsg());
        assertTrue(entry.getMessage().contains("dev-group"), "告警文案应含被监控 bot");
    }

    @Test
    void doesNotFireWhenWithinThreshold() {
        AlertRule r = rule(1L, true, 30, 30, null);
        when(rules.findAll()).thenReturn(Collections.singletonList(r));
        when(messageLog.lastCreatedAt("dev-group")).thenReturn(System.currentTimeMillis() - 10 * MINUTE);

        scheduler.check();

        verify(sender, never()).send(any(), any(), any(), any(), any(), any());
        verify(rules, never()).setLastAlertAt(anyLong(), any());
        verify(alertLog, never()).insert(any());
    }

    @Test
    void doesNotFireWhenTheBotHasNoHistory() {
        // 从未发过任何消息：无从判断是掉线还是还没开始用，不告警（用户确认）。
        AlertRule r = rule(1L, true, 30, 30, null);
        when(rules.findAll()).thenReturn(Collections.singletonList(r));
        when(messageLog.lastCreatedAt("dev-group")).thenReturn(null);

        scheduler.check();

        verify(sender, never()).send(any(), any(), any(), any(), any(), any());
        verify(alertLog, never()).insert(any());
    }

    @Test
    void staysSilentDuringCooldown() {
        long now = System.currentTimeMillis();
        // 10 分钟前刚告警过，冷却 30 分钟还没到，尽管仍超时，也不该重复发。
        AlertRule r = rule(1L, true, 30, 30, now - 10 * MINUTE);
        when(rules.findAll()).thenReturn(Collections.singletonList(r));
        when(messageLog.lastCreatedAt("dev-group")).thenReturn(now - 40 * MINUTE);

        scheduler.check();

        verify(sender, never()).send(any(), any(), any(), any(), any(), any());
        verify(rules, never()).setLastAlertAt(anyLong(), any());
        verify(alertLog, never()).insert(any());
    }

    @Test
    void firesAgainAfterTheCooldownElapses() {
        long now = System.currentTimeMillis();
        // 40 分钟前告警过，冷却 30 分钟已过，仍超时 => 再发一条。
        AlertRule r = rule(1L, true, 30, 30, now - 40 * MINUTE);
        when(rules.findAll()).thenReturn(Collections.singletonList(r));
        when(messageLog.lastCreatedAt("dev-group")).thenReturn(now - 60 * MINUTE);
        when(registry.get("ops-group")).thenReturn(alertBot());
        when(sender.send(eq("ops-group"), any(FeishuProperties.Bot.class), any(byte[].class),
                any(JsonNode.class), eq("text"), eq("alert-scheduler")))
                .thenReturn(SendResult.localError("ops-group", 200, 0, "ok"));

        scheduler.check();

        verify(sender).send(eq("ops-group"), any(FeishuProperties.Bot.class), any(byte[].class),
                any(JsonNode.class), eq("text"), eq("alert-scheduler"));
    }

    @Test
    void resetsAlertStateOnRecovery() {
        // 之前告警过，现在有新消息（恢复）=> 清掉 last_alert_at，下次再超时会重新立刻告警。
        AlertRule r = rule(1L, true, 30, 30, 12345L);
        when(rules.findAll()).thenReturn(Collections.singletonList(r));
        when(messageLog.lastCreatedAt("dev-group")).thenReturn(System.currentTimeMillis() - 5 * MINUTE);

        scheduler.check();

        verify(rules).setLastAlertAt(eq(1L), isNull());
        verify(sender, never()).send(any(), any(), any(), any(), any(), any());
    }

    @Test
    void disabledRuleDoesNotFire() {
        AlertRule r = rule(1L, false, 30, 30, null);
        when(rules.findAll()).thenReturn(Collections.singletonList(r));
        when(messageLog.lastCreatedAt("dev-group")).thenReturn(System.currentTimeMillis() - 40 * MINUTE);

        scheduler.check();

        verify(sender, never()).send(any(), any(), any(), any(), any(), any());
        verify(alertLog, never()).insert(any());
    }

    @Test
    void recordsANormalRun() {
        // 每次调度执行都要落一条日志：正常扫描 1 条规则、未触发告警。
        AlertRule r = rule(1L, true, 30, 30, null);
        when(rules.findAll()).thenReturn(Collections.singletonList(r));
        when(messageLog.lastCreatedAt("dev-group")).thenReturn(System.currentTimeMillis() - 10 * MINUTE);

        scheduler.check();

        ArgumentCaptor<AlertRunLog> captor = ArgumentCaptor.forClass(AlertRunLog.class);
        verify(runLog).insert(captor.capture());
        AlertRunLog entry = captor.getValue();
        assertEquals("ok", entry.getStatus());
        assertEquals(1, entry.getRulesScanned());
        assertEquals(0, entry.getAlertsFired());
    }

    @Test
    void recordsASkippedRunWhenRulesAreUnavailable() {
        // 规则表不可用（findAll 返回 null）=> 本轮跳过，仍要留一条 skipped 日志。
        when(rules.findAll()).thenReturn(null);

        scheduler.check();

        ArgumentCaptor<AlertRunLog> captor = ArgumentCaptor.forClass(AlertRunLog.class);
        verify(runLog).insert(captor.capture());
        AlertRunLog entry = captor.getValue();
        assertEquals("skipped", entry.getStatus());
        assertEquals(0, entry.getRulesScanned());
        assertEquals(0, entry.getAlertsFired());
    }

    @Test
    void fireForTestSendsAndLogsWithoutTouchingCooldown() {
        AlertRule r = rule(1L, true, 30, 30, null);
        when(registry.get("ops-group")).thenReturn(alertBot());
        when(sender.send(eq("ops-group"), any(FeishuProperties.Bot.class), any(byte[].class),
                any(JsonNode.class), eq("text"), eq("alert-scheduler")))
                .thenReturn(SendResult.localError("ops-group", 200, 0, "ok"));

        SendResult result = scheduler.fireForTest(r);

        assertNotNull(result);
        verify(sender).send(eq("ops-group"), any(FeishuProperties.Bot.class), any(byte[].class),
                any(JsonNode.class), eq("text"), eq("alert-scheduler"));
        // 测试触发不污染真实冷却状态。
        verify(rules, never()).setLastAlertAt(anyLong(), any());

        ArgumentCaptor<AlertLog> captor = ArgumentCaptor.forClass(AlertLog.class);
        verify(alertLog).insert(captor.capture());
        assertEquals(30, captor.getValue().getIdleMinutes(), "模拟的 idle 时长等于阈值");
        assertEquals("ops-group", captor.getValue().getAlertBotKey());
    }
}
