package com.example.feishuproxy.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 一条告警事件记录：每当 {@code AlertScheduler} 判定某条规则触发告警，就往 {@code alert_log} 追加一行，
 * 记录触发它的规则、被监控 bot、目标 bot、阈值、空闲时长、告警文案与发送结果，供后台「告警日志」页回溯。
 * <p>
 * 与 {@link AlertRunLog}（每轮调度一条汇总）不同，这里一条告警一行，且含完整文案与发送结果。只追加、不清理。
 */
@TableName("alert_log")
public class AlertLog {

    /** 自增主键；未落库前为 null。用包装类型，MyBatis-Plus 才能正确识别 AUTO 并回填生成键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 触发本次告警的规则 id。 */
    private Long ruleId;
    /** 被监控的 botKey（即规则里的 botKey）。 */
    private String botKey;
    /** 告警消息发往的 botKey。 */
    private String alertBotKey;
    /** 规则的超时阈值（分钟）。 */
    private int thresholdMinutes;
    /** 触发时该 bot 已空闲的分钟数。 */
    private int idleMinutes;
    /** 实际发送的告警文案（含关键词前缀）。 */
    private String message;
    /** 发送结果码：0=成功；-1=未发送（目标 bot 未知/停用或组包失败）。 */
    private int sendCode;
    /** 发送结果说明（如飞书返回的 msg 或本地失败原因）。 */
    private String sendMsg;
    /** 触发时间（epoch 毫秒）。 */
    private long triggeredAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public String getBotKey() {
        return botKey;
    }

    public void setBotKey(String botKey) {
        this.botKey = botKey;
    }

    public String getAlertBotKey() {
        return alertBotKey;
    }

    public void setAlertBotKey(String alertBotKey) {
        this.alertBotKey = alertBotKey;
    }

    public int getThresholdMinutes() {
        return thresholdMinutes;
    }

    public void setThresholdMinutes(int thresholdMinutes) {
        this.thresholdMinutes = thresholdMinutes;
    }

    public int getIdleMinutes() {
        return idleMinutes;
    }

    public void setIdleMinutes(int idleMinutes) {
        this.idleMinutes = idleMinutes;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getSendCode() {
        return sendCode;
    }

    public void setSendCode(int sendCode) {
        this.sendCode = sendCode;
    }

    public String getSendMsg() {
        return sendMsg;
    }

    public void setSendMsg(String sendMsg) {
        this.sendMsg = sendMsg;
    }

    public long getTriggeredAt() {
        return triggeredAt;
    }

    public void setTriggeredAt(long triggeredAt) {
        this.triggeredAt = triggeredAt;
    }
}
