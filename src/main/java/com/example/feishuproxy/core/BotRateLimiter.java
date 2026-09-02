package com.example.feishuproxy.core;

/**
 * 针对单个机器人的限流器，同时执行飞书的两条限制：每分钟 100 次、每秒 5 次请求。
 * <p>
 * 两个桶在同一把锁内完成检查和扣减。如果一次只处理一个，就得先扣分钟桶，等第二个桶拒绝时
 * 再把令牌还回去——这种退还路径在并发下会漏掉令牌。
 */
public class BotRateLimiter {

    private final Bucket minuteBucket;
    private final Bucket secondBucket;
    private final Object lock = new Object();

    public BotRateLimiter(int perMinute, int perSecond) {
        this.minuteBucket = new Bucket(perMinute, perMinute / 60.0d);
        this.secondBucket = new Bucket(perSecond, perSecond);
    }

    /**
     * @param maxWaitMs 调用方可以阻塞等待许可的时长；0 表示立即失败
     * @return 当许可被授予（且已扣减）时返回 true
     */
    public boolean tryAcquire(long maxWaitMs) {
        long deadlineNanos = System.nanoTime() + Math.max(0L, maxWaitMs) * 1_000_000L;

        synchronized (lock) {
            while (true) {
                long now = System.nanoTime();
                minuteBucket.refill(now);
                secondBucket.refill(now);

                if (minuteBucket.hasToken() && secondBucket.hasToken()) {
                    minuteBucket.consume();
                    secondBucket.consume();
                    return true;
                }

                long remainingNanos = deadlineNanos - now;
                if (remainingNanos <= 0) {
                    return false;
                }

                long neededNanos = Math.max(minuteBucket.nanosUntilToken(), secondBucket.nanosUntilToken());
                long waitNanos = Math.min(remainingNanos, neededNanos);
                try {
                    // 没有人调用 notify()；这里的 wait 只是释放监视器的休眠，
                    // 让其他机器人的线程——以及本机器人的其他线程——在此期间不被阻塞。
                    lock.wait(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    /** 令牌桶。容量为非正数表示「此维度不限制」。 */
    private static final class Bucket {

        private final boolean unlimited;
        private final double capacity;
        private final double refillPerSecond;
        private double tokens;
        private long lastRefillNanos;

        Bucket(double capacity, double refillPerSecond) {
            this.unlimited = capacity <= 0 || refillPerSecond <= 0;
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        void refill(long now) {
            if (unlimited) {
                return;
            }
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0d;
            if (elapsedSeconds > 0) {
                tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
                lastRefillNanos = now;
            }
        }

        boolean hasToken() {
            return unlimited || tokens >= 1.0d;
        }

        void consume() {
            if (!unlimited) {
                tokens -= 1.0d;
            }
        }

        long nanosUntilToken() {
            if (unlimited || tokens >= 1.0d) {
                return 0L;
            }
            // +1 是为了永远不计算出 0 的等待时长，从而避免空转。
            return (long) ((1.0d - tokens) / refillPerSecond * 1_000_000_000.0d) + 1L;
        }
    }
}
