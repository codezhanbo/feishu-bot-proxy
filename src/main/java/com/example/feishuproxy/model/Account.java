package com.example.feishuproxy.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 一条「我的账号」记录，对应 {@code account} 表的一行。
 * <p>
 * 账号是手动添加的：{@code accountId}（查询时的玩家昵称）与 {@code level}（等级）由后台手工维护；
 * {@code banStatus}（正常/封禁）、{@code lastCheckedAt}（最后查询时间）与 {@code totalMatches}
 * （总场次）在对该账号执行封禁查询时由 {@code AccountRepository.updateFromCheck} 自动更新。
 */
@TableName("account")
public class Account {

    /** 账号ID，即查询时的玩家昵称；主键，建好后不可改。 */
    @TableId(type = IdType.INPUT)
    private String accountId;
    /** 封禁状态：正常 / 封禁。 */
    private String banStatus;
    /** 等级（手动维护，可为空）。 */
    private String level;
    /** 平台：steam / kakao / console / xbox / psn；封禁查询按它选平台。旧行 null，按 steam 处理。 */
    private String platform;
    /** 最后一次查询时间，格式 {@code yyyy-MM-dd HH:mm:ss}；从未查询过时为 null。 */
    private String lastCheckedAt;
    /** 总场次（对局数）；尚未查到过时为 null。 */
    private Long totalMatches;

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getBanStatus() {
        return banStatus;
    }

    public void setBanStatus(String banStatus) {
        this.banStatus = banStatus;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(String lastCheckedAt) {
        this.lastCheckedAt = lastCheckedAt;
    }

    public Long getTotalMatches() {
        return totalMatches;
    }

    public void setTotalMatches(Long totalMatches) {
        this.totalMatches = totalMatches;
    }
}
