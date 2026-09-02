package com.example.feishuproxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 中转服务的全部可调参数，绑定自 application.yml 的 {@code feishu.*} 配置段。
 */
@ConfigurationProperties(prefix = "feishu")
public class FeishuProperties {

    /** botKey -> 机器人定义。botKey 是调用方放在 URL 里的那段：/webhook/{botKey}。 */
    private Map<String, Bot> bots = new LinkedHashMap<>();

    /** 当路径中没有 botKey 时，POST /webhook 使用的机器人。可选。 */
    private String defaultBot;

    /** 当非空时，调用方必须携带匹配的 X-Api-Token 请求头。为空则关闭校验。 */
    private String accessToken = "";

    private int connectTimeoutMs = 3000;

    private int readTimeoutMs = 5000;

    /** 飞书会拒绝超过 20KB 的 webhook 报文，所以我们直接快速失败，省去一次往返。 */
    private int maxBodyBytes = 20480;

    private Retry retry = new Retry();

    private RateLimit rateLimit = new RateLimit();

    private Store store = new Store();

    public Map<String, Bot> getBots() {
        return bots;
    }

    public void setBots(Map<String, Bot> bots) {
        this.bots = bots;
    }

    public String getDefaultBot() {
        return defaultBot;
    }

    public void setDefaultBot(String defaultBot) {
        this.defaultBot = defaultBot;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Store getStore() {
        return store;
    }

    public void setStore(Store store) {
        this.store = store;
    }

    /** 一个飞书自定义机器人，即一个群聊。 */
    public static class Bot {

        /** 完整的 webhook 地址：https://open.feishu.cn/open-apis/bot/v2/hook/xxxxxxxx */
        private String webhook;

        /** 群里「安全设置 - 加签」得到的 Secret。设置后，中转服务会对每个请求加签。 */
        private String secret = "";

        private boolean enabled = true;

        /**
         * 在飞书侧配置的关键词。中转服务并不校验它们（由飞书校验），
         * 仅用于构造 POST /admin/test/{botKey} 的合法消息。
         */
        private List<String> keywords = new ArrayList<>();

        public String getWebhook() {
            return webhook;
        }

        public void setWebhook(String webhook) {
            this.webhook = webhook;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getKeywords() {
            return keywords;
        }

        public void setKeywords(List<String> keywords) {
            this.keywords = keywords;
        }

        public boolean hasSecret() {
            return secret != null && !secret.isEmpty();
        }
    }

    public static class Retry {

        /** 总尝试次数，含第一次。设为 1 表示不重试。 */
        private int maxAttempts = 3;

        private long initialBackoffMs = 500;

        private double multiplier = 2.0;

        private long maxBackoffMs = 5000;

        /**
         * 值得重试的飞书业务码：9499（请求过多）、19003（系统繁忙）、11232（频率超限）。
         * <p>
         * 11232 是<strong>租户级</strong>限流，整个租户下所有自定义机器人共享额度，
         * 在整点、半点尤其容易撞上——本地限流器管不到它，只能退避重试。
         * <p>
         * 放在配置里，是为了能根据真实流量调整而不必重新构建。
         */
        private Set<Integer> retryableCodes = new LinkedHashSet<>(Arrays.asList(9499, 19003, 11232));

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getInitialBackoffMs() {
            return initialBackoffMs;
        }

        public void setInitialBackoffMs(long initialBackoffMs) {
            this.initialBackoffMs = initialBackoffMs;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public long getMaxBackoffMs() {
            return maxBackoffMs;
        }

        public void setMaxBackoffMs(long maxBackoffMs) {
            this.maxBackoffMs = maxBackoffMs;
        }

        public Set<Integer> getRetryableCodes() {
            return retryableCodes;
        }

        public void setRetryableCodes(Set<Integer> retryableCodes) {
            this.retryableCodes = retryableCodes;
        }
    }

    /** 飞书允许每个自定义机器人每分钟 100 次请求，同时每秒 5 次请求。 */
    public static class RateLimit {

        private boolean enabled = true;

        private int perMinute = 100;

        private int perSecond = 5;

        /** 调用方在等待许可时可以阻塞多久，超时后我们就回 429。 */
        private long waitTimeoutMs = 1000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPerMinute() {
            return perMinute;
        }

        public void setPerMinute(int perMinute) {
            this.perMinute = perMinute;
        }

        public int getPerSecond() {
            return perSecond;
        }

        public void setPerSecond(int perSecond) {
            this.perSecond = perSecond;
        }

        public long getWaitTimeoutMs() {
            return waitTimeoutMs;
        }

        public void setWaitTimeoutMs(long waitTimeoutMs) {
            this.waitTimeoutMs = waitTimeoutMs;
        }
    }

    /** 每条入站消息的持久化位置。一个 Postgres 库，只追加，从不清理。 */
    public static class Store {

        private boolean enabled = true;

        /** 完整 JDBC URL，如 {@code jdbc:postgresql://.../postgres?sslmode=require}。 */
        private String jdbcUrl = "";

        private String username = "";

        private String password = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        public void setJdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
