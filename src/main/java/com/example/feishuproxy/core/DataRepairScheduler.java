package com.example.feishuproxy.core;

import com.example.feishuproxy.store.BanCheckLogRepository;
import com.example.feishuproxy.store.MessageLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 老数据修复调度器：早期版本用 {@code ZoneId.systemDefault()}（容器里是 UTC）把可读时间列
 * （{@code message_log.create_datetime}、{@code ban_check_log.queried_datetime}）烧成了慢 8 小时的值。
 * 写入侧改成固定北京时间后，这里按权威的 epoch 毫秒列（{@code created_at} / {@code queried_at}）
 * 把旧行的可读时间重算回填。
 * <p>
 * 幂等、可反复跑：一轮修完，之后每轮扫描到的不一致行为 0，不会重复写。每轮尽力而为——
 * 任一表中途出错只记日志，不影响另一表，也不影响任何业务请求。
 */
@Component
public class DataRepairScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataRepairScheduler.class);

    /** 单批扫描行数，避免一次把整张表拖进内存。 */
    private static final int BATCH = 200;

    private final MessageLogRepository messageLog;
    private final BanCheckLogRepository banCheckLog;

    public DataRepairScheduler(MessageLogRepository messageLog, BanCheckLogRepository banCheckLog) {
        this.messageLog = messageLog;
        this.banCheckLog = banCheckLog;
    }

    @Scheduled(fixedDelayString = "${feishu.repair.interval-ms:3600000}")
    public void repair() {
        long startedAt = System.currentTimeMillis();
        int msgFixed = messageLog.repairCreateDatetime(BATCH);
        int banFixed = banCheckLog.repairQueriedDatetime(BATCH);
        log.info("legacy data repair done (messageLog={} banCheckLog={} in {}ms)",
                msgFixed, banFixed, System.currentTimeMillis() - startedAt);
    }
}