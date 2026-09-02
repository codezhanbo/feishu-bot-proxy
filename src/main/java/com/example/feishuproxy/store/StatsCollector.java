package com.example.feishuproxy.store;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** GET /admin/stats 背后的计数器。仅内存保存，重启即清零。 */
@Component
public class StatsCollector {

    private final long startedAtMillis = System.currentTimeMillis();

    private final LongAdder total = new LongAdder();
    private final LongAdder success = new LongAdder();
    private final LongAdder failure = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder totalCostMs = new LongAdder();

    private final Map<String, BotCounter> byBot = new ConcurrentHashMap<>();
    private final Map<Integer, LongAdder> byCode = new ConcurrentHashMap<>();

    public void record(String botKey, boolean ok, int code, long costMs) {
        total.increment();
        totalCostMs.add(costMs);
        if (ok) {
            success.increment();
        } else {
            failure.increment();
        }
        byCode.computeIfAbsent(code, k -> new LongAdder()).increment();

        BotCounter counter = byBot.computeIfAbsent(botKey, k -> new BotCounter());
        counter.total.increment();
        if (ok) {
            counter.success.increment();
        } else {
            counter.failure.increment();
        }
        counter.lastSendMillis = System.currentTimeMillis();
    }

    /** 被本地限流器拒掉、因而从未离开中转服务的发送。 */
    public void recordRejected(String botKey) {
        rejected.increment();
        byBot.computeIfAbsent(botKey, k -> new BotCounter()).rejected.increment();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        long totalCount = total.sum();
        long successCount = success.sum();

        out.put("startedAt", startedAtMillis);
        out.put("uptimeSeconds", (System.currentTimeMillis() - startedAtMillis) / 1000L);
        out.put("total", totalCount);
        out.put("success", successCount);
        out.put("failure", failure.sum());
        out.put("rejectedByRateLimit", rejected.sum());
        out.put("successRate", rate(successCount, totalCount));
        out.put("avgCostMs", totalCount == 0 ? 0L : totalCostMs.sum() / totalCount);

        Map<String, Object> bots = new LinkedHashMap<>();
        for (Map.Entry<String, BotCounter> entry : byBot.entrySet()) {
            BotCounter counter = entry.getValue();
            long botTotal = counter.total.sum();
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("total", botTotal);
            stats.put("success", counter.success.sum());
            stats.put("failure", counter.failure.sum());
            stats.put("rejectedByRateLimit", counter.rejected.sum());
            stats.put("successRate", rate(counter.success.sum(), botTotal));
            stats.put("lastSendMillis", counter.lastSendMillis);
            bots.put(entry.getKey(), stats);
        }
        out.put("byBot", bots);

        Map<Integer, Long> codes = new TreeMap<>();
        for (Map.Entry<Integer, LongAdder> entry : byCode.entrySet()) {
            codes.put(entry.getKey(), entry.getValue().sum());
        }
        out.put("byCode", codes);
        out.put("note", "in-memory counters, reset on restart");
        return out;
    }

    private static double rate(long part, long whole) {
        if (whole == 0) {
            return 0d;
        }
        return Math.round(part * 10000d / whole) / 10000d;
    }

    private static final class BotCounter {
        private final LongAdder total = new LongAdder();
        private final LongAdder success = new LongAdder();
        private final LongAdder failure = new LongAdder();
        private final LongAdder rejected = new LongAdder();
        private volatile long lastSendMillis;
    }
}
