package com.example.feishuproxy.core;

import com.example.feishuproxy.config.FeishuProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(new FeishuProperties());

    @Test
    void retriesTransientTransportFailures() {
        assertTrue(policy.isRetryable(true, 0, 0), "network error");
        assertTrue(policy.isRetryable(false, 500, 0), "server error");
        assertTrue(policy.isRetryable(false, 503, 0), "service unavailable");
        assertTrue(policy.isRetryable(false, 429, 0), "http rate limited");
    }

    @Test
    void retriesFeishuThrottlingAndBusyCodes() {
        assertTrue(policy.isRetryable(false, 200, 9499), "9499 too many request");
        assertTrue(policy.isRetryable(false, 200, 19003), "19003 system busy");
    }

    @Test
    void doesNotRetryDeterministicBusinessFailures() {
        assertFalse(policy.isRetryable(false, 200, 19021), "bad signature will fail again");
        assertFalse(policy.isRetryable(false, 200, 19024), "keyword not matched will fail again");
        assertFalse(policy.isRetryable(false, 200, 19001), "bad parameter");
        assertFalse(policy.isRetryable(false, 200, 19022), "ip not allowed");
        assertFalse(policy.isRetryable(false, 200, 10001), "unknown webhook");
    }

    @Test
    void doesNotRetryOtherClientErrors() {
        assertFalse(policy.isRetryable(false, 400, 0));
        assertFalse(policy.isRetryable(false, 404, 0));
    }

    @Test
    void doesNotRetrySuccess() {
        assertFalse(policy.isRetryable(false, 200, 0));
    }

    @Test
    void backoffGrowsAndStaysWithinTheJitterBand() {
        FeishuProperties properties = new FeishuProperties();
        properties.getRetry().setInitialBackoffMs(500);
        properties.getRetry().setMultiplier(2.0);
        properties.getRetry().setMaxBackoffMs(5000);
        RetryPolicy custom = new RetryPolicy(properties);

        for (int i = 0; i < 50; i++) {
            long first = custom.backoffMillis(0);
            long second = custom.backoffMillis(1);
            assertTrue(first >= 250 && first <= 500, "attempt 0 backoff was " + first);
            assertTrue(second >= 500 && second <= 1000, "attempt 1 backoff was " + second);
        }
    }

    @Test
    void backoffIsCappedByMaxBackoff() {
        FeishuProperties properties = new FeishuProperties();
        properties.getRetry().setInitialBackoffMs(500);
        properties.getRetry().setMultiplier(10.0);
        properties.getRetry().setMaxBackoffMs(1000);
        RetryPolicy custom = new RetryPolicy(properties);

        assertTrue(custom.backoffMillis(5) <= 1000);
    }

    @Test
    void maxAttemptsIsAtLeastOne() {
        FeishuProperties properties = new FeishuProperties();
        properties.getRetry().setMaxAttempts(0);
        assertEquals(1, new RetryPolicy(properties).maxAttempts());
        assertEquals(3, policy.maxAttempts());
    }
}
