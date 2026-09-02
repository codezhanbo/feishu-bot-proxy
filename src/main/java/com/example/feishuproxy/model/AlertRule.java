package com.example.feishuproxy.model;

/**
 * 一条存活告警规则：监控某个 bot（{@code botKey}）在 {@code message_log} 里是否已有
 * {@code thresholdMinutes} 分钟没有新消息记录，超时则向 {@code alertBotKey} 发告警。
 * <p>
 * {@code lastAlertAt} 记录最近一次告警发出的时间（epoch 毫秒），null 表示当前未处于告警态。
 * 它既用于冷却（超时持续期间按 {@code cooldownMinutes} 间隔重复提醒），也用于去重——
 * 所以需要持久化到 {@code alert_rule} 表，重启后仍保持正确。
 */
public class AlertRule {

    /** 自增主键；未落库前为 0。 */
    private long id;
    /** 被监控的 botKey（即 dev-bot）。 */
    private String botKey;
    /** 超时阈值（分钟）：最近一条消息距今超过它就算超时。 */
    private int thresholdMinutes;
    /** 冷却间隔（分钟）：超时持续期间，隔这么久再重复发一条告警。 */
    private int cooldownMinutes;
    /** 监控开关。关闭后不检查、不发告警。 */
    private boolean enabled;
    /** 触发时把告警消息发往的 botKey。 */
    private String alertBotKey;
    /** 最近一次告警发出时间（epoch 毫秒），null = 当前未处于告警态。 */
    private Long lastAlertAt;
    private long createdAt;
    private long updatedAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getBotKey() {
        return botKey;
    }

    public void setBotKey(String botKey) {
        this.botKey = botKey;
    }

    public int getThresholdMinutes() {
        return thresholdMinutes;
    }

    public void setThresholdMinutes(int thresholdMinutes) {
        this.thresholdMinutes = thresholdMinutes;
    }

    public int getCooldownMinutes() {
        return cooldownMinutes;
    }

    public void setCooldownMinutes(int cooldownMinutes) {
        this.cooldownMinutes = cooldownMinutes;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAlertBotKey() {
        return alertBotKey;
    }

    public void setAlertBotKey(String alertBotKey) {
        this.alertBotKey = alertBotKey;
    }

    public Long getLastAlertAt() {
        return lastAlertAt;
    }

    public void setLastAlertAt(Long lastAlertAt) {
        this.lastAlertAt = lastAlertAt;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
