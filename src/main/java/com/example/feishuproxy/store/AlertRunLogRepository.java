package com.example.feishuproxy.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.feishuproxy.model.AlertRunLog;
import com.example.feishuproxy.store.mapper.AlertRunLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 告警调度执行日志的后端存储，改用 MyBatis-Plus 的 {@link AlertRunLogMapper}。
 * 每分钟追加一行、低频查询，复用「读失败返回 null、写失败抛 {@link IllegalStateException}」的语义约定。
 * 表只追加、不清理。
 */
@Component
public class AlertRunLogRepository {

    private static final Logger log = LoggerFactory.getLogger(AlertRunLogRepository.class);

    /** 单页上限，避免过大的 limit 把整张表拉进内存。 */
    public static final int MAX_PAGE = 200;

    private final AlertRunLogMapper mapper;

    public AlertRunLogRepository(AlertRunLogMapper mapper) {
        this.mapper = mapper;
    }

    /** 追加一行执行日志。写失败抛 {@link IllegalStateException}（由调度器静默捕获）。 */
    public void insert(AlertRunLog logEntry) {
        try {
            mapper.insert(logEntry);
        } catch (Exception e) {
            throw new IllegalStateException("alert run log store unavailable", e);
        }
    }

    /** 最新在前（无过滤条件的便捷重载）。数据库不可用时返回 {@code null}。 */
    public List<AlertRunLog> query(int limit, int offset) {
        return query(null, null, null, limit, offset);
    }

    /**
     * 最新在前，按执行时间（即创建时间）倒序。可选按状态 / 执行时间区间过滤：
     * {@code status} 非空则精确匹配，{@code from}/{@code to} 是 epoch 毫秒的半开区间 {@code [from, to)}。
     * 数据库不可用时返回 {@code null}。
     */
    public List<AlertRunLog> query(String status, Long from, Long to, int limit, int offset) {
        try {
            int pageLimit = Math.max(1, Math.min(limit, MAX_PAGE));
            int pageOffset = Math.max(0, offset);
            return mapper.selectList(conditions(status, from, to)
                    .orderByDesc(AlertRunLog::getExecutedAt)
                    .orderByDesc(AlertRunLog::getId)
                    .last("LIMIT " + pageLimit + " OFFSET " + pageOffset));
        } catch (Exception e) {
            log.warn("failed to query alert run log", e);
            return null;
        }
    }

    /** 全表行数（无过滤条件的便捷重载）；数据库不可用时返回 0。表只追加，COUNT 开销可忽略。 */
    public long total() {
        return count(null, null, null);
    }

    /** 符合条件（与 {@link #query(String, Long, Long, int, int)} 同款过滤）的行数；数据库不可用时返回 0。 */
    public long count(String status, Long from, Long to) {
        try {
            Long count = mapper.selectCount(conditions(status, from, to));
            return count == null ? 0L : count;
        } catch (Exception e) {
            log.warn("failed to count alert run log", e);
            return 0L;
        }
    }

    /** 组装状态 / 时间区间的过滤条件，供查询与计数共用。 */
    private LambdaQueryWrapper<AlertRunLog> conditions(String status, Long from, Long to) {
        LambdaQueryWrapper<AlertRunLog> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(AlertRunLog::getStatus, status.trim());
        }
        if (from != null) {
            wrapper.ge(AlertRunLog::getExecutedAt, from);
        }
        if (to != null) {
            wrapper.lt(AlertRunLog::getExecutedAt, to);
        }
        return wrapper;
    }
}
