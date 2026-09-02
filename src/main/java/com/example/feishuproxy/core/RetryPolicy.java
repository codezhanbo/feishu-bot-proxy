package com.example.feishuproxy.core;

import com.example.feishuproxy.config.FeishuProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 判断哪些失败值得再试一次。
 * <p>
 * 会重试的：网络错误（连接/读取超时）、HTTP 5xx、HTTP 429，以及 {@code feishu.retry.retryable-codes}
 * 里列出的飞书业务码（9499 请求过多、19003 系统繁忙、11232 频率超限）。
 * 不重试的：其他 4xx，以及确定性的业务失败，例如 19021（签名错误）、19024（关键词不匹配）、
 * 19001（参数错误）、19022（IP 不允许）、10001（未知 webhook）——重放这些只会白白消耗限流额度。
 */
@Component
public class RetryPolicy {

    private final FeishuProperties.Retry config;

    public RetryPolicy(FeishuProperties properties) {
        this.config = properties.getRetry();
    }

    public int maxAttempts() {
        return Math.max(1, config.getMaxAttempts());
    }

    public boolean isRetryable(boolean networkError, int httpStatus, int feishuCode) {
        if (networkError) {
            return true;
        }
        if (httpStatus >= 500 || httpStatus == 429) {
            return true;
        }
        if (httpStatus >= 400) {
            return false;
        }
        return config.getRetryableCodes().contains(feishuCode);
    }

    /**
     * 指数退避并叠加全抖动，这样同一时刻一起触发 9499 的一波调用方不会步调一致地重试。
     *
     * @param attemptIndex 刚刚失败的那次尝试的下标（从 0 开始）
     */
    public long backoffMillis(int attemptIndex) {
        double base = config.getInitialBackoffMs() * Math.pow(config.getMultiplier(), attemptIndex);
        long capped = (long) Math.min(base, config.getMaxBackoffMs());
        if (capped <= 0) {
            return 0L;
        }
        return (long) (capped * (0.5d + ThreadLocalRandom.current().nextDouble() * 0.5d));
    }
}
