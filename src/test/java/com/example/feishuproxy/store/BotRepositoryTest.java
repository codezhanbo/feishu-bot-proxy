package com.example.feishuproxy.store;

import com.example.feishuproxy.config.FeishuProperties;
import com.example.feishuproxy.store.mapper.BotMapper;
import com.example.feishuproxy.store.mapper.SettingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 机器人的 CRUD 与 default-bot 读写。首次启动种子由 {@link BotRepositorySeedingTest} 单独覆盖。 */
@SpringBootTest
class BotRepositoryTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @DynamicPropertySource
    static void h2(DynamicPropertyRegistry registry) {
        registry.add("feishu.store.jdbc-url", () ->
                "jdbc:h2:mem:bots" + SEQ.incrementAndGet()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private BotRepository repository;

    @Autowired
    private BotMapper botMapper;

    @Autowired
    private SettingMapper settingMapper;

    @BeforeEach
    void clear() {
        botMapper.delete(null);
        settingMapper.delete(null);
    }

    private static FeishuProperties.Bot bot(String webhook, String secret, boolean enabled, String... keywords) {
        FeishuProperties.Bot bot = new FeishuProperties.Bot();
        bot.setWebhook(webhook);
        bot.setSecret(secret);
        bot.setEnabled(enabled);
        bot.setKeywords(Arrays.asList(keywords));
        return bot;
    }

    @Test
    void crudRoundTrips() {
        assertEquals(0, repository.findAll().size());
        assertNull(repository.find("dev"));

        repository.insert("dev", bot("https://open.feishu.cn/open-apis/bot/v2/hook/aaaabbbbccccdddd",
                "sec", true, "告警"));
        assertEquals(1, repository.findAll().size());

        FeishuProperties.Bot loaded = repository.find("dev");
        assertNotNull(loaded);
        assertEquals("sec", loaded.getSecret());
        assertTrue(loaded.isEnabled());
        assertEquals(Arrays.asList("告警"), loaded.getKeywords());
        assertEquals("https://open.feishu.cn/open-apis/bot/v2/hook/aaaabbbbccccdddd", loaded.getWebhook());

        repository.update("dev", bot("https://open.feishu.cn/open-apis/bot/v2/hook/eeeeffff",
                "", false));
        loaded = repository.find("dev");
        assertFalse(loaded.isEnabled());
        assertFalse(loaded.hasSecret(), "空密钥应清掉加签");
        assertEquals("https://open.feishu.cn/open-apis/bot/v2/hook/eeeeffff", loaded.getWebhook());

        repository.delete("dev");
        assertNull(repository.find("dev"));
        assertEquals(0, repository.findAll().size());
    }

    @Test
    void defaultBotCanBeSetAndCleared() {
        assertEquals("", repository.getDefaultBot(), "未设置返回空串");
        repository.setDefaultBot("dev");
        assertEquals("dev", repository.getDefaultBot());
        repository.setDefaultBot("");
        assertEquals("", repository.getDefaultBot(), "空串清除默认机器人");
    }
}
