package com.example.feishuproxy.store;

import com.example.feishuproxy.config.FeishuProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotRepositoryTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static String freshH2() {
        return "jdbc:h2:mem:bot" + SEQ.incrementAndGet()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    private static FeishuProperties props(String jdbcUrl, Map<String, FeishuProperties.Bot> bots,
                                          String defaultBot) {
        FeishuProperties properties = new FeishuProperties();
        properties.getStore().setJdbcUrl(jdbcUrl);
        properties.getStore().setUsername("sa");
        properties.getStore().setPassword("");
        properties.setBots(bots);
        properties.setDefaultBot(defaultBot);
        return properties;
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
    void seedsBotsAndDefaultBotOnlyWhenTheTableIsEmpty() {
        String url = freshH2();
        Map<String, FeishuProperties.Bot> seeds = new LinkedHashMap<>();
        seeds.put("dev-group", bot("https://open.feishu.cn/open-apis/bot/v2/hook/aaaabbbbccccdddd",
                "s1", true, "告警", "构建"));

        BotRepository first = new BotRepository(props(url, seeds, "dev-group"));

        assertEquals(1, first.findAll().size());
        assertEquals("dev-group", first.getDefaultBot());
        FeishuProperties.Bot loaded = first.find("dev-group");
        assertNotNull(loaded);
        assertEquals("s1", loaded.getSecret());
        assertEquals(Arrays.asList("告警", "构建"), loaded.getKeywords());
        assertTrue(loaded.isEnabled());

        // 第二次开仓，换一批种子：表已非空，不再导入；default-bot 也保留原值。
        Map<String, FeishuProperties.Bot> seeds2 = new LinkedHashMap<>();
        seeds2.put("ops-group", bot("https://open.feishu.cn/open-apis/bot/v2/hook/zzzz", "", true));
        BotRepository second = new BotRepository(props(url, seeds2, "ops-group"));

        assertEquals(1, second.findAll().size(), "非空表不再 seed");
        assertNull(second.find("ops-group"));
        assertEquals("dev-group", second.getDefaultBot(), "已设的 default-bot 不再被覆盖");
    }

    @Test
    void crudRoundTrips() {
        String url = freshH2();
        BotRepository repository = new BotRepository(props(url, Collections.emptyMap(), null));
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
        String url = freshH2();
        BotRepository repository = new BotRepository(props(url, Collections.emptyMap(), null));

        assertEquals("", repository.getDefaultBot(), "未设置返回空串");
        repository.setDefaultBot("dev");
        assertEquals("dev", repository.getDefaultBot());
        repository.setDefaultBot("");
        assertEquals("", repository.getDefaultBot(), "空串清除默认机器人");
    }

    @Test
    void returnsNullWhenTheDatabaseCannotBeReached() {
        BotRepository repository = new BotRepository(props(
                "jdbc:postgresql://127.0.0.1:1/nope?connectTimeout=1", Collections.emptyMap(), null));

        assertNull(repository.findAll());
        assertNull(repository.getDefaultBot());
    }

    @Test
    void throwsWhenNotConfigured() {
        // 不配 jdbc-url：无数据库。读返回 null，写抛异常。
        FeishuProperties properties = new FeishuProperties();
        BotRepository repository = new BotRepository(properties);

        assertNull(repository.findAll());
        assertNull(repository.getDefaultBot());
        assertThrows(IllegalStateException.class, () -> repository.insert("x", new FeishuProperties.Bot()));
        assertThrows(IllegalStateException.class, () -> repository.delete("x"));
    }
}
