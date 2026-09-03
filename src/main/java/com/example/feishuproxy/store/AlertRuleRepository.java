package com.example.feishuproxy.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.feishuproxy.model.AlertRule;
import com.example.feishuproxy.store.mapper.AlertRuleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 告警规则的后端存储，改用 MyBatis-Plus 的 {@link AlertRuleMapper}。
 * 告警规则是低频配置——只在后台增删改和调度器每轮检查时读写，复用「读失败返回 null、
 * 写失败抛 {@link IllegalStateException}」的语义约定。
 */
@Component
public class AlertRuleRepository {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleRepository.class);

    private final AlertRuleMapper mapper;

    public AlertRuleRepository(AlertRuleMapper mapper) {
        this.mapper = mapper;
    }

    /** 全部规则，按 id 升序。数据库不可用时返回 {@code null}。 */
    public List<AlertRule> findAll() {
        try {
            return mapper.selectList(new LambdaQueryWrapper<AlertRule>().orderByAsc(AlertRule::getId));
        } catch (Exception e) {
            log.warn("failed to load alert rules", e);
            return null;
        }
    }

    /** 单条规则。数据库不可用时抛异常；不存在时返回 {@code null}。 */
    public AlertRule find(long id) {
        try {
            return mapper.selectById(id);
        } catch (Exception e) {
            throw new IllegalStateException("alert rule store unavailable", e);
        }
    }

    /** 新增一条规则，返回自增主键。 */
    public long insert(AlertRule rule) {
        long now = System.currentTimeMillis();
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        try {
            mapper.insert(rule);
            return rule.getId();
        } catch (Exception e) {
            throw new IllegalStateException("alert rule store unavailable", e);
        }
    }

    /** 更新一条规则的配置项（不含 {@code last_alert_at}，那列由调度器单独维护）。 */
    public void update(AlertRule rule) {
        try {
            mapper.update(null, new LambdaUpdateWrapper<AlertRule>()
                    .eq(AlertRule::getId, rule.getId())
                    .set(AlertRule::getBotKey, rule.getBotKey())
                    .set(AlertRule::getThresholdMinutes, rule.getThresholdMinutes())
                    .set(AlertRule::getCooldownMinutes, rule.getCooldownMinutes())
                    .set(AlertRule::isEnabled, rule.isEnabled() ? 1 : 0)
                    .set(AlertRule::getAlertBotKey, rule.getAlertBotKey())
                    .set(AlertRule::getUpdatedAt, System.currentTimeMillis()));
        } catch (Exception e) {
            throw new IllegalStateException("alert rule store unavailable", e);
        }
    }

    public void delete(long id) {
        try {
            mapper.deleteById(id);
        } catch (Exception e) {
            throw new IllegalStateException("alert rule store unavailable", e);
        }
    }

    /** 记录一次告警发出（或重置为 null，表示恢复）。由调度器维护，不经过 update()。 */
    public void setLastAlertAt(long id, Long epochMillis) {
        try {
            mapper.update(null, new LambdaUpdateWrapper<AlertRule>()
                    .eq(AlertRule::getId, id)
                    .set(AlertRule::getLastAlertAt, epochMillis)
                    .set(AlertRule::getUpdatedAt, System.currentTimeMillis()));
        } catch (Exception e) {
            throw new IllegalStateException("alert rule store unavailable", e);
        }
    }
}
