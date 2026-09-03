package com.example.feishuproxy.core;

import com.example.feishuproxy.model.Account;
import com.example.feishuproxy.model.BanCheckLog;
import com.example.feishuproxy.model.BanCheckResult;
import com.example.feishuproxy.store.AccountRepository;
import com.example.feishuproxy.store.BanCheckLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 账号封禁状态批量巡检调度器：按固定周期（默认 30 分钟）把账号表里所有账号逐个查一遍封禁状态
 * 与等级，回填账号表，并往 {@code ban_check_log} 追加一条查询日志。
 * <p>
 * 逐个串行调用上游，避免并发打爆 pubg.hk；单个账号失败不影响其余账号，也不影响下一轮。
 * 首轮在应用启动后立即执行一次（Spring fixedDelay 的默认行为），此后每隔一个周期再跑。
 */
@Component
public class BanCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(BanCheckScheduler.class);

    private final PubgBanClient client;
    private final AccountRepository accounts;
    private final BanCheckLogRepository banCheckLog;

    public BanCheckScheduler(PubgBanClient client, AccountRepository accounts,
                             BanCheckLogRepository banCheckLog) {
        this.client = client;
        this.accounts = accounts;
        this.banCheckLog = banCheckLog;
    }

    @Scheduled(fixedDelayString = "${feishu.ban-check.interval-ms:1800000}")
    public void run() {
        List<Account> all = accounts.findAll();
        if (all == null) {
            log.warn("ban-check scan skipped: account store unavailable");
            return;
        }
        if (all.isEmpty()) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        int success = 0;
        int failed = 0;
        for (Account account : all) {
            String name = account.getAccountId();
            String platform = account.getPlatform() == null || account.getPlatform().trim().isEmpty()
                    ? "steam" : account.getPlatform().trim();
            BanCheckResult result = client.check(name, platform);
            // 每次查询都留档（成功失败都写），这是查询的旁路，写失败不抛异常。
            banCheckLog.record(name, platform, result);
            if (result.isSuccess()) {
                accounts.updateFromCheck(name, AccountRepository.toBanStatus(result),
                        result.getLevelText(),
                        result.getTotalMatches() == null ? null : result.getTotalMatches().longValue(),
                        BanCheckLog.format(System.currentTimeMillis()));
                success++;
            } else {
                failed++;
            }
        }
        log.info("ban-check scan done (accounts={} success={} failed={} in {}ms)",
                all.size(), success, failed, System.currentTimeMillis() - startedAt);
    }
}
