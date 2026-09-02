package com.example.feishuproxy.core;

import com.example.feishuproxy.config.FeishuProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 为每个 botKey 惰性创建并缓存一个 {@link BotRateLimiter}。 */
@Component
public class RateLimiterRegistry {

    private final FeishuProperties properties;
    private final Map<String, BotRateLimiter> limiters = new ConcurrentHashMap<>();

    public RateLimiterRegistry(FeishuProperties properties) {
        this.properties = properties;
    }

    public BotRateLimiter get(String botKey) {
        return limiters.computeIfAbsent(botKey, key -> new BotRateLimiter(
                properties.getRateLimit().getPerMinute(),
                properties.getRateLimit().getPerSecond()));
    }

    /** 丢弃缓存的限流器，让配置重载后从全新的桶开始。 */
    public void reset() {
        limiters.clear();
    }
}
