package com.example.feishuproxy.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.feishuproxy.store.typehandler.BooleanToIntTypeHandler;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 一条封禁查询记录：对应 {@code ban_check_log} 的一行、GET /console/ban-logs 的一条记录。
 * <p>
 * 成功与失败共用同一个类型：失败时 {@link #isSuccess()} 为 false，结果类字段（封禁状态、
 * 类型、对局数、总场次、等级、siteUUID）全为 null，错误原因放在 {@link #getError()}。
 */
@TableName("ban_check_log")
public class BanCheckLog {

    /**
     * 展示用固定时区：落库时间以 epoch 毫秒存储、本与时区无关，格式化必须用北京时间。
     * 不能用 {@link ZoneId#systemDefault()}——容器基础镜像（eclipse-temurin）默认 UTC，会慢 8 小时。
     */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZONE);

    @TableId(type = IdType.AUTO)
    private Long id;
    private String player;
    private String platform;
    @TableField(typeHandler = BooleanToIntTypeHandler.class)
    private boolean success;
    private String banStatus;
    private String banType;
    private Long matchCount;
    private Long totalMatches;
    private String level;
    private String siteUuid;
    private String error;
    private long queriedAt;
    private String queriedDatetime;

    /** epoch 毫秒 → {@code yyyy-MM-dd HH:mm:ss}。账号表的 {@code last_checked_at} 也走这里，保证同一格式。 */
    public static String format(long epochMillis) {
        return FORMATTER.format(Instant.ofEpochMilli(epochMillis));
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPlayer() {
        return player;
    }

    public void setPlayer(String player) {
        this.player = player;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getBanStatus() {
        return banStatus;
    }

    public void setBanStatus(String banStatus) {
        this.banStatus = banStatus;
    }

    public String getBanType() {
        return banType;
    }

    public void setBanType(String banType) {
        this.banType = banType;
    }

    public Long getMatchCount() {
        return matchCount;
    }

    public void setMatchCount(Long matchCount) {
        this.matchCount = matchCount;
    }

    public Long getTotalMatches() {
        return totalMatches;
    }

    public void setTotalMatches(Long totalMatches) {
        this.totalMatches = totalMatches;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getSiteUuid() {
        return siteUuid;
    }

    public void setSiteUuid(String siteUuid) {
        this.siteUuid = siteUuid;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public long getQueriedAt() {
        return queriedAt;
    }

    public void setQueriedAt(long queriedAt) {
        this.queriedAt = queriedAt;
    }

    public String getQueriedDatetime() {
        return queriedDatetime;
    }

    public void setQueriedDatetime(String queriedDatetime) {
        this.queriedDatetime = queriedDatetime;
    }
}
