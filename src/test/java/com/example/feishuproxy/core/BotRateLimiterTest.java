package com.example.feishuproxy.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotRateLimiterTest {

    @Test
    void allowsABurstUpToThePerSecondCapacity() {
        BotRateLimiter limiter = new BotRateLimiter(100, 5);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(0), "permit " + i + " should be granted");
        }
        assertFalse(limiter.tryAcquire(0), "6th call within the same second must be refused");
    }

    @Test
    void enforcesThePerMinuteCapEvenWhenPerSecondIsRoomy() {
        BotRateLimiter limiter = new BotRateLimiter(3, 1000);
        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));
        assertFalse(limiter.tryAcquire(0), "minute bucket is empty");
    }

    @Test
    void waitsForARefillWhenGivenTime() {
        BotRateLimiter limiter = new BotRateLimiter(100, 1);
        assertTrue(limiter.tryAcquire(0));
        assertFalse(limiter.tryAcquire(0), "second bucket drained");

        long started = System.currentTimeMillis();
        assertTrue(limiter.tryAcquire(3000), "should block until the bucket refills");
        long waited = System.currentTimeMillis() - started;
        assertTrue(waited >= 500, "expected to actually wait, waited " + waited + "ms");
    }

    @Test
    void treatsNonPositiveLimitsAsUnlimited() {
        BotRateLimiter limiter = new BotRateLimiter(0, 0);
        for (int i = 0; i < 1000; i++) {
            assertTrue(limiter.tryAcquire(0));
        }
    }

    @Test
    void doesNotOverGrantUnderConcurrency() throws Exception {
        int capacity = 50;
        BotRateLimiter limiter = new BotRateLimiter(capacity, 10000);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger granted = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 10; i++) {
                        if (limiter.tryAcquire(0)) {
                            granted.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        // 分钟桶按 capacity/60 每秒的速率回填，所以慢机器在整段运行期间可能会多发出一两个
        // 令牌——但绝不会发出请求的那 200 个。
        assertTrue(granted.get() >= capacity && granted.get() <= capacity + 2,
                "granted " + granted.get() + ", expected about " + capacity);
    }
}
