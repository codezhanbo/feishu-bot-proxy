package com.example.feishuproxy.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 从一条战报里解析出来的游戏统计数据，对应 {@code message_log} 的 5 个统计列。
 * <p>
 * {@link #getStatDate()} 和 {@link #getSurvivalLevel()} 是报文里的原值；其余三个是
 * <strong>增量</strong>——与同 botKey 上一条战报的差值。报文给的全是累计值，
 * 而真正有意义的是这一天涨了多少。
 * <p>
 * 每个字段都可能为 null：报文里缺这一项，或者压根找不到可比对的上一条。
 * null 表示「算不出」，和 0（「没涨」）是两回事，所以用包装类型而不是基本类型。
 */
public class GameStats {

    private final String statDate;
    private final Integer survivalLevel;
    private final Long expGained;
    private final Long bpGained;
    private final String duration;

    public GameStats(String statDate, Integer survivalLevel, Long expGained, Long bpGained, String duration) {
        this.statDate = statDate;
        this.survivalLevel = survivalLevel;
        this.expGained = expGained;
        this.bpGained = bpGained;
        this.duration = duration;
    }

    /** 战报自己声明的日期，取自标题，格式 {@code yyyy-MM-dd}。不是这一行的落库时间。 */
    public String getStatDate() {
        return statDate;
    }

    /** 生存等级。这是当前值，不做差分。 */
    public Integer getSurvivalLevel() {
        return survivalLevel;
    }

    /** 本次新增的生存经验（不是通行经验）。 */
    public Long getExpGained() {
        return expGained;
    }

    /** 本次新增的 BP。 */
    public Long getBpGained() {
        return bpGained;
    }

    /** 本次新增的对局时长，{@code HH:MM:SS}。累计值倒退时会带负号。 */
    public String getDuration() {
        return duration;
    }

    /**
     * 五项全空说明这行压根不是战报，存 null 比存一个空壳更诚实。
     * <p>
     * {@code @JsonIgnore}：这是给仓储层判断用的，不是接口字段。不加的话 Jackson 会把它
     * 当成 {@code isXxx()} 形式的属性，在 /admin/logs 里凭空多出一个 {@code "empty"}。
     */
    @JsonIgnore
    public boolean isEmpty() {
        return statDate == null && survivalLevel == null
                && expGained == null && bpGained == null && duration == null;
    }
}
