package com.example.feishuproxy.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.feishuproxy.model.AlertLog;
import com.example.feishuproxy.store.mapper.AlertLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 告警事件日志的后端存储，改用 MyBatis-Plus 的 {@link AlertLogMapper}。
 * 每触发一次告警追加一行、低频查询，复用「读失败返回 null、写失败抛 {@link IllegalStateException}」的语义约定。
 * 表只追加、不清理。
 */
@Component
public class AlertLogRepository {

    private static final Logger log = LoggerFactory.getLogger(AlertLogRepository.class);

    /** 单页上限，避免过大的 limit 把整张表拉进内存。 */
    public static final int MAX_PAGE = 200;

    private final AlertLogMapper mapper;

    public AlertLogRepository(AlertLogMapper mapper) {
        this.mapper = mapper;
    }

    /** 追加一行告警事件日志。写失败抛 {@link IllegalStateException}（由调度器静默捕获）。 */
    public void insert(AlertLog entry) {
        try {
            mapper.insert(entry);
        } catch (Exception e) {
            throw new IllegalStateException("alert log store unavailable", e);
        }
    }

    /** 最新在前（无过滤条件的便捷重载）。数据库不可用时返回 {@code null}。 */
    public List<AlertLog> query(int limit, int offset) {
        return query(null, null, null, limit, offset);
    }

    /**
     * 最新在前，按触发时间倒序。可选按被监控 bot / 触发时间区间过滤：
     * {@code botKey} 非空则对 {@code bot_key} 列做模糊匹配，{@code from}/{@code to} 是
     * {@code triggered_at} 的 epoch 毫秒半开区间 {@code [from, to)}。数据库不可用时返回 {@code null}。
     */
    public List<AlertLog> query(String botKey, Long from, Long to, int limit, int offset) {
        try {
            int pageLimit = Math.max(1, Math.min(limit, MAX_PAGE));
            int pageOffset = Math.max(0, offset);
            return mapper.selectList(conditions(botKey, from, to)
                    .orderByDesc(AlertLog::getTriggeredAt)
                    .orderByDesc(AlertLog::getId)
                    .last("LIMIT " + pageLimit + " OFFSET " + pageOffset));
        } catch (Exception e) {
            log.warn("failed to query alert log", e);
            return null;
        }
    }

    /** 全表行数（无过滤条件的便捷重载）；数据库不可用时返回 0。表只追加，COUNT 开销可忽略。 */
    public long total() {
        return count(null, null, null);
    }

    /** 符合条件（与 {@link #query(String, Long, Long, int, int)} 同款过滤）的行数；数据库不可用时返回 0。 */
    public long count(String botKey, Long from, Long to) {
        try {
            Long count = mapper.selectCount(conditions(botKey, from, to));
            return count == null ? 0L : count;
        } catch (Exception e) {
            log.warn("failed to count alert log", e);
            return 0L;
        }
    }

    /** 组装 bot / 触发时间区间的过滤条件，供查询与计数共用。 */
    private LambdaQueryWrapper<AlertLog> conditions(String botKey, Long from, Long to) {
        LambdaQueryWrapper<AlertLog> wrapper = new LambdaQueryWrapper<>();
        if (botKey != null && !botKey.trim().isEmpty()) {
            wrapper.like(AlertLog::getBotKey, botKey.trim());
        }
        if (from != null) {
            wrapper.ge(AlertLog::getTriggeredAt, from);
        }
        if (to != null) {
            wrapper.lt(AlertLog::getTriggeredAt, to);
        }
        return wrapper;
    }
}
