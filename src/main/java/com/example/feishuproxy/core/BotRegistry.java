package com.example.feishuproxy.core;

import com.example.feishuproxy.config.FeishuProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把 URL 中的 botKey 解析成机器人定义。
 * <p>
 * 通过 {@link AtomicReference} 持有一份不可变快照，使读取无锁，且将来重载配置时可以原子地
 * 替换整张 map。
 */
@Component
public class BotRegistry {

    private final AtomicReference<Map<String, FeishuProperties.Bot>> snapshot = new AtomicReference<>();

    public BotRegistry(FeishuProperties properties) {
        replace(properties.getBots());
    }

    public void replace(Map<String, FeishuProperties.Bot> bots) {
        Map<String, FeishuProperties.Bot> copy = new LinkedHashMap<>();
        if (bots != null) {
            copy.putAll(bots);
        }
        snapshot.set(Collections.unmodifiableMap(copy));
    }

    public FeishuProperties.Bot get(String botKey) {
        return snapshot.get().get(botKey);
    }

    public Map<String, FeishuProperties.Bot> all() {
        return snapshot.get();
    }

    /**
     * 规范化 {botKey} 路径段。一个请求只针对一个群；为空白的路径段意味着「没有目标」，
     * 会被调用方拒绝。
     */
    public static String normalizeBotKey(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /**
     * webhook 地址就是凭据——任何持有它的人都能向群里发消息。绝不要完整地记录或暴露它们。
     */
    public static String maskWebhook(String webhook) {
        if (webhook == null || webhook.isEmpty()) {
            return "";
        }
        int slash = webhook.lastIndexOf('/');
        String token = slash >= 0 ? webhook.substring(slash + 1) : webhook;
        String prefix = slash >= 0 ? webhook.substring(0, slash + 1) : "";
        if (token.length() <= 8) {
            return prefix + "****";
        }
        return prefix + token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }
}
