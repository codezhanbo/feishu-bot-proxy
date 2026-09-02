package com.example.feishuproxy.core;

import com.example.feishuproxy.config.FeishuProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotRegistryTest {

    @Test
    void trimsTheBotKey() {
        assertEquals("dev-group", BotRegistry.normalizeBotKey("  dev-group "));
    }

    @Test
    void returnsEmptyForNullOrBlank() {
        assertTrue(BotRegistry.normalizeBotKey(null).isEmpty());
        assertTrue(BotRegistry.normalizeBotKey("  ").isEmpty());
    }

    @Test
    void aCommaIsJustPartOfTheKeyNow() {
        // 广播已移除，normalizeBotKey 不再拆分。"a,b" 是一个整体的 key，
        // 由 WebhookController 拒掉（400/40002），这里只管别把它切开。
        assertEquals("a,b", BotRegistry.normalizeBotKey("a,b"));
    }

    @Test
    void looksUpConfiguredBotsAndReportsDisabledOnes() {
        FeishuProperties.Bot enabled = new FeishuProperties.Bot();
        enabled.setWebhook("https://open.feishu.cn/open-apis/bot/v2/hook/aaaaaaaa");

        FeishuProperties.Bot disabled = new FeishuProperties.Bot();
        disabled.setWebhook("https://open.feishu.cn/open-apis/bot/v2/hook/bbbbbbbb");
        disabled.setEnabled(false);

        Map<String, FeishuProperties.Bot> bots = new LinkedHashMap<>();
        bots.put("dev", enabled);
        bots.put("ops", disabled);

        FeishuProperties properties = new FeishuProperties();
        properties.setBots(bots);
        BotRegistry registry = new BotRegistry(properties);

        assertNotNull(registry.get("dev"));
        assertTrue(registry.get("dev").isEnabled());
        assertFalse(registry.get("ops").isEnabled());
        assertNull(registry.get("nope"));
        assertEquals(2, registry.all().size());
    }

    @Test
    void masksTheWebhookTokenBecauseItIsACredential() {
        String masked = BotRegistry.maskWebhook("https://open.feishu.cn/open-apis/bot/v2/hook/abcdefgh12345678");
        assertEquals("https://open.feishu.cn/open-apis/bot/v2/hook/abcd****5678", masked);
        assertFalse(masked.contains("efgh1234"));
    }

    @Test
    void masksShortTokensEntirely() {
        assertEquals("https://x/y/****", BotRegistry.maskWebhook("https://x/y/short"));
        assertEquals("", BotRegistry.maskWebhook(null));
    }

    @Test
    void snapshotIsImmutableSoCallersCannotMutateConfig() {
        FeishuProperties properties = new FeishuProperties();
        BotRegistry registry = new BotRegistry(properties);
        Map<String, FeishuProperties.Bot> all = registry.all();
        try {
            all.put("x", new FeishuProperties.Bot());
            throw new AssertionError("registry snapshot should be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // 符合预期
        }
    }
}
