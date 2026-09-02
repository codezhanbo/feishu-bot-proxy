package com.example.feishuproxy.store;

import com.example.feishuproxy.config.FeishuProperties;
import com.example.feishuproxy.model.AlertRule;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertRuleRepositoryTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static String freshH2() {
        return "jdbc:h2:mem:alert" + SEQ.incrementAndGet()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    private static AlertRuleRepository repository(String jdbcUrl) {
        FeishuProperties properties = new FeishuProperties();
        properties.getStore().setJdbcUrl(jdbcUrl);
        properties.getStore().setUsername("sa");
        properties.getStore().setPassword("");
        return new AlertRuleRepository(properties);
    }

    private static AlertRule rule(String botKey, int threshold, int cooldown, boolean enabled,
                                  String alertBotKey) {
        AlertRule rule = new AlertRule();
        rule.setBotKey(botKey);
        rule.setThresholdMinutes(threshold);
        rule.setCooldownMinutes(cooldown);
        rule.setEnabled(enabled);
        rule.setAlertBotKey(alertBotKey);
        return rule;
    }

    @Test
    void crudRoundTrips() {
        AlertRuleRepository repository = repository(freshH2());
        assertEquals(0, repository.findAll().size());
        assertNull(repository.find(1L));

        long id = repository.insert(rule("dev-group", 30, 30, true, "ops-group"));
        assertTrue(id > 0, "insert 要返回自增主键");

        AlertRule loaded = repository.find(id);
        assertNotNull(loaded);
        assertEquals("dev-group", loaded.getBotKey());
        assertEquals(30, loaded.getThresholdMinutes());
        assertEquals(30, loaded.getCooldownMinutes());
        assertTrue(loaded.isEnabled());
        assertEquals("ops-group", loaded.getAlertBotKey());
        assertNull(loaded.getLastAlertAt(), "新规则还没有告警过");

        AlertRule toUpdate = rule("dev-group", 60, 15, false, "ops-group");
        toUpdate.setId(id);
        repository.update(toUpdate);
        loaded = repository.find(id);
        assertEquals(60, loaded.getThresholdMinutes());
        assertEquals(15, loaded.getCooldownMinutes());
        assertFalse(loaded.isEnabled());

        repository.delete(id);
        assertNull(repository.find(id));
        assertEquals(0, repository.findAll().size());
    }

    @Test
    void lastAlertAtIsPersistedSeparatelyFromConfig() {
        AlertRuleRepository repository = repository(freshH2());
        long id = repository.insert(rule("dev-group", 30, 30, true, "ops-group"));

        long now = System.currentTimeMillis();
        repository.setLastAlertAt(id, now);
        assertEquals(Long.valueOf(now), repository.find(id).getLastAlertAt());

        // update() 不碰 last_alert_at：改配置不该抹掉告警态。
        AlertRule toUpdate = rule("dev-group", 45, 45, true, "ops-group");
        toUpdate.setId(id);
        repository.update(toUpdate);
        assertEquals(Long.valueOf(now), repository.find(id).getLastAlertAt(),
                "update 是配置项，不得动 last_alert_at");

        repository.setLastAlertAt(id, null);
        assertNull(repository.find(id).getLastAlertAt(), "null 表示恢复，清掉告警态");
    }

    @Test
    void returnsNullWhenNotConfigured() {
        AlertRuleRepository repository = new AlertRuleRepository(new FeishuProperties());

        assertNull(repository.findAll());
        assertThrows(IllegalStateException.class,
                () -> repository.insert(rule("dev", 30, 30, true, "ops")));
        assertThrows(IllegalStateException.class, () -> repository.delete(1L));
    }
}
