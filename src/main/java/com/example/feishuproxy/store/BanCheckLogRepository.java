package com.example.feishuproxy.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.feishuproxy.model.BanCheckLog;
import com.example.feishuproxy.model.BanCheckResult;
import com.example.feishuproxy.store.mapper.BanCheckLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 封禁查询记录的后端存储：每次封禁查询（成功或失败）追加一行到 {@code ban_check_log}，
 * 改用 MyBatis-Plus 的 {@link BanCheckLogMapper}。
 * <p>
 * 记录只是查询的旁路，绝不能影响查询应答——所以 {@link #record} 尽力而为，失败只记日志、
 * 不抛异常；查询与计数沿用「读失败返回 null、计数返回 0」的语义约定。表只追加、不清理。
 */
@Component
public class BanCheckLogRepository {

    private static final Logger log = LoggerFactory.getLogger(BanCheckLogRepository.class);

    /** 单页上限，避免过大的 limit 把整张表拉进内存。 */
    public static final int MAX_PAGE = 200;

    private final BanCheckLogMapper mapper;

    public BanCheckLogRepository(BanCheckLogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 追加一行查询记录，成功失败都写。绝不抛异常——记录失败不能改变已经答复给查询方的结果。
     *
     * @param player   查询的玩家昵称（已 trim）
     * @param platform 平台，如 steam / kakao
     * @param result   本次查询的结果
     */
    public void record(String player, String platform, BanCheckResult result) {
        try {
            insert(player, platform, result);
        } catch (Exception e) {
            log.warn("failed to persist ban check log (player={} platform={})", player, platform, e);
        }
    }

    private void insert(String player, String platform, BanCheckResult result) {
        BanCheckLog record = new BanCheckLog();
        record.setPlayer(player);
        record.setPlatform(platform);
        record.setSuccess(result.isSuccess());
        if (result.isSuccess()) {
            record.setBanStatus(result.getBanStatus());
            record.setBanType(result.getBanType());
            record.setMatchCount(result.getMatchCount() == null ? null : result.getMatchCount().longValue());
            record.setTotalMatches(result.getTotalMatches() == null ? null : result.getTotalMatches().longValue());
            record.setLevel(result.getLevelText());
            record.setSiteUuid(result.getSiteUUID());
        } else {
            record.setError(result.getError());
        }
        record.setQueriedAt(System.currentTimeMillis());
        record.setQueriedDatetime(BanCheckLog.format(record.getQueriedAt()));
        mapper.insert(record);
    }

    /** 最新在前。数据库不可用时返回 {@code null}。 */
    public List<BanCheckLog> query(int limit, int offset) {
        try {
            int pageLimit = Math.max(1, Math.min(limit, MAX_PAGE));
            int pageOffset = Math.max(0, offset);
            return mapper.selectList(new LambdaQueryWrapper<BanCheckLog>()
                    .orderByDesc(BanCheckLog::getId)
                    .last("LIMIT " + pageLimit + " OFFSET " + pageOffset));
        } catch (Exception e) {
            log.warn("failed to query ban check log", e);
            return null;
        }
    }

    /** 全表行数；数据库不可用时返回 0。表只追加，COUNT 开销可忽略。 */
    public long total() {
        try {
            Long count = mapper.selectCount(null);
            return count == null ? 0L : count;
        } catch (Exception e) {
            log.warn("failed to count ban check log", e);
            return 0L;
        }
    }
}
