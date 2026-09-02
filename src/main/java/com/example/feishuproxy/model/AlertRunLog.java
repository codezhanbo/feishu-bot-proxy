package com.example.feishuproxy.model;

/**
 * 一条告警调度执行记录：每次 {@code AlertScheduler.check()} 跑完写一行，落到 {@code alert_run_log} 表，
 * 供后台「调度日志」页查询。只追加，不清理。
 */
public class AlertRunLog {

    /** 自增主键；未落库前为 0。 */
    private long id;
    /** 本轮执行的起始时间（epoch 毫秒）。 */
    private long executedAt;
    /** 执行结果：{@code ok} 正常跑完；{@code skipped} 规则库不可用，本轮跳过。 */
    private String status;
    /** 本轮扫描到的规则总数。 */
    private int rulesScanned;
    /** 本轮实际发出的告警数。 */
    private int alertsFired;
    /** 本轮耗时（毫秒）。 */
    private long durationMs;
    /** 触发告警的规则明细（JSON 数组文本），本轮没有告警时为 null。 */
    private String detail;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(long executedAt) {
        this.executedAt = executedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getRulesScanned() {
        return rulesScanned;
    }

    public void setRulesScanned(int rulesScanned) {
        this.rulesScanned = rulesScanned;
    }

    public int getAlertsFired() {
        return alertsFired;
    }

    public void setAlertsFired(int alertsFired) {
        this.alertsFired = alertsFired;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
