package com.example.feishuproxy.core;

import com.example.feishuproxy.config.FeishuProperties;
import com.example.feishuproxy.store.BotRepository;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把 URL 中的 botKey 解析成机器人定义。机器人定义来自数据库（{@link BotRepository}），
 * 不再是 yaml。
 * <p>
 * 通过 {@link AtomicReference} 持有一份不可变快照，使读取无锁；后台增删改后调用 {@link #reload()}
 * 原子地替换整张 map 和 default-bot。数据库不可用时保留旧快照，绝不清空——即使 Supabase 短暂断连，
 * 已加载的机器人照样转发。
 */
@Component
public class BotRegistry {

    private final AtomicReference<Map<String, FeishuProperties.Bot>> snapshot =
            new AtomicReference<>(Collections.<String, FeishuProperties.Bot>emptyMap());

    private final AtomicReference<String> defaultBot = new AtomicReference<>("");

    private final BotRepository repository;

    public BotRegistry(BotRepository repository) {
        this.repository = repository;
        reload();
    }

    public void replace(Map<String, FeishuProperties.Bot> bots) {
        Map<String, FeishuProperties.Bot> copy = new LinkedHashMap<>();
        if (bots != null) {
            copy.putAll(bots);
        }
        snapshot.set(Collections.unmodifiableMap(copy));
    }

    /**
     * 从数据库重新加载 bots 与 default-bot。二者任一读不到（数据库不可用）就整体保留旧快照。
     */
    public void reload() {
        if (repository == null) {
            return;
        }
        Map<String, FeishuProperties.Bot> bots = repository.findAll();
        if (bots == null) {
            return;
        }
        String def = repository.getDefaultBot();
        if (def == null) {
            return;
        }
        replace(bots);
        defaultBot.set(def);
    }

    public FeishuProperties.Bot get(String botKey) {
        return snapshot.get().get(botKey);
    }

    public Map<String, FeishuProperties.Bot> all() {
        return snapshot.get();
    }

    /** 当前生效的默认机器人（POST /webhook 不带 botKey 时用）。未设置则为空串。 */
    public String getDefaultBot() {
        return defaultBot.get();
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
