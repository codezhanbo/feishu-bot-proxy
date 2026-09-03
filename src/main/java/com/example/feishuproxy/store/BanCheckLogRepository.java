package com.example.feishuproxy.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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

    /** 最新在前（无过滤条件的便捷重载）。数据库不可用时返回 {@code null}。 */
    public List<BanCheckLog> query(int limit, int offset) {
        return query(null, null, null, limit, offset);
    }

    /**
     * 最新在前，按 id 倒序。可选按玩家 ID 模糊 / 查询时间区间过滤：
     * {@code player} 非空则对 {@code player} 列做模糊匹配，{@code from}/{@code to} 是 epoch 毫秒的半开区间 {@code [from, to)}。
     * 数据库不可用时返回 {@code null}。
     */
    public List<BanCheckLog> query(String player, Long from, Long to, int limit, int offset) {
        try {
            int pageLimit = Math.max(1, Math.min(limit, MAX_PAGE));
            int pageOffset = Math.max(0, offset);
            List<BanCheckLog> rows = mapper.selectList(conditions(player, from, to)
                    .orderByDesc(BanCheckLog::getId)
                    .last("LIMIT " + pageLimit + " OFFSET " + pageOffset));
            // 展示时间由正确的 epoch（queried_at）实时重算，避免旧行写入时烧死的 UTC 值慢 8 小时。
            for (BanCheckLog row : rows) {
                row.setQueriedDatetime(BanCheckLog.format(row.getQueriedAt()));
            }
            return rows;
        } catch (Exception e) {
            log.warn("failed to query ban check log", e);
            return null;
        }
    }

    /** 全表行数（无过滤条件的便捷重载）；数据库不可用时返回 0。表只追加，COUNT 开销可忽略。 */
    public long total() {
        return count(null, null, null);
    }

    /** 符合条件（与 {@link #query(String, Long, Long, int, int)} 同款过滤）的行数；数据库不可用时返回 0。 */
    public long count(String player, Long from, Long to) {
        try {
            Long count = mapper.selectCount(conditions(player, from, to));
            return count == null ? 0L : count;
        } catch (Exception e) {
            log.warn("failed to count ban check log", e);
            return 0L;
        }
    }

    /**
     * 修复老数据：早期版本把 {@code queried_datetime} 烧成了慢 8 小时的 UTC 值，这里按权威的
     * {@code queried_at}（epoch 毫秒）重算，只改不一致的行。幂等、可反复跑。库不可用或中途
     * 出错返回 0（只记日志，绝不抛异常）。
     *
     * @return 本轮实际修复的行数
     */
    public int repairQueriedDatetime(int batchSize) {
        int fixed = 0;
        int limit = Math.max(1, Math.min(batchSize, MAX_PAGE));
        Long afterId = null;
        try {
            while (true) {
                LambdaQueryWrapper<BanCheckLog> wrapper = new LambdaQueryWrapper<BanCheckLog>()
                        .select(BanCheckLog::getId, BanCheckLog::getQueriedAt, BanCheckLog::getQueriedDatetime)
                        .orderByAsc(BanCheckLog::getId)
                        .last("LIMIT " + limit);
                if (afterId != null) {
                    wrapper.gt(BanCheckLog::getId, afterId);
                }
                List<BanCheckLog> batch = mapper.selectList(wrapper);
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                for (BanCheckLog e : batch) {
                    String correct = BanCheckLog.format(e.getQueriedAt());
                    if (!correct.equals(e.getQueriedDatetime())) {
                        mapper.update(null, new LambdaUpdateWrapper<BanCheckLog>()
                                .eq(BanCheckLog::getId, e.getId())
                                .set(BanCheckLog::getQueriedDatetime, correct));
                        fixed++;
                    }
                }
                if (batch.size() < limit) {
                    break; // 已到最后一页
                }
                afterId = batch.get(batch.size() - 1).getId();
            }
        } catch (Exception e) {
            log.warn("failed to repair ban_check_log queried_datetime", e);
        }
        return fixed;
    }

    /** 组装玩家 ID / 查询时间区间的过滤条件，供查询与计数共用。 */
    private LambdaQueryWrapper<BanCheckLog> conditions(String player, Long from, Long to) {
        LambdaQueryWrapper<BanCheckLog> wrapper = new LambdaQueryWrapper<>();
        if (player != null && !player.trim().isEmpty()) {
            wrapper.like(BanCheckLog::getPlayer, player.trim());
        }
        if (from != null) {
            wrapper.ge(BanCheckLog::getQueriedAt, from);
        }
        if (to != null) {
            wrapper.lt(BanCheckLog::getQueriedAt, to);
        }
        return wrapper;
    }
}
