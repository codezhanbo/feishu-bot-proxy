package com.example.feishuproxy.store;

import com.example.feishuproxy.config.FeishuProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首次启动种子：{@code bot} 表为空时从 {@code feishu.bots} 灌入、{@code default-bot} 未设时写入
 * {@code app_setting}。用独立上下文 + 唯一 H2 库，保证构造函数里的 seed 在一个空表上跑一次。
 */
@SpringBootTest
class BotRepositorySeedingTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @DynamicPropertySource
    static void h2(DynamicPropertyRegistry registry) {
        registry.add("feishu.store.jdbc-url", () ->
                "jdbc:h2:mem:seed" + SEQ.incrementAndGet()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private BotRepository repository;

    @Test
    void seedsBotsAndDefaultBotFromConfigurationOnFirstStartup() {
        Map<String, FeishuProperties.Bot> all = repository.findAll();
        // 与 src/test/resources/application.yml 里的 feishu.bots 保持一致。
        assertEquals(4, all.size(), "plain/signed/second/paused 四台都该从配置灌入");
        assertEquals("", all.get("plain").getSecret());
        assertEquals("test-secret", all.get("signed").getSecret());
        assertTrue(all.get("second").isEnabled());
        assertFalse(all.get("paused").isEnabled(), "paused 配置为 disabled，种子要原样保留");
        assertEquals("plain", repository.getDefaultBot());
    }
}
