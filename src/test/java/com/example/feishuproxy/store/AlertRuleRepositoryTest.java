package com.example.feishuproxy.store;

import com.example.feishuproxy.model.AlertRule;
import com.example.feishuproxy.store.mapper.AlertRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class AlertRuleRepositoryTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @DynamicPropertySource
    static void h2(DynamicPropertyRegistry registry) {
        registry.add("feishu.store.jdbc-url", () ->
                "jdbc:h2:mem:alert" + SEQ.incrementAndGet()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private AlertRuleRepository repository;

    @Autowired
    private AlertRuleMapper mapper;

    @BeforeEach
    void clear() {
        mapper.delete(null);
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
}
