package com.example.feishuproxy.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * pubg.hk 封禁查询的一次结果。只挑后台页面用得上的字段，舍弃了体积很大的 {@code matchIDs} 数组。
 * <p>
 * 成功与失败共用同一个类型：失败时 {@link #isSuccess()} 为 false，其余字段全为 null，
 * 错误原因放在 {@link #getError()}。这样客户端无需用异常或 null 来表达「查无此人」这类上游结果。
 * <p>
 * 等级不是单一字段：{@code survivalTier} 是 1~5 段，{@code survivalLevel} 是段内 1~500 级，
 * 展示时拼成「段 + 级」的形式（如 5段109级），见 {@link #getLevelText()}。
 */
public final class BanCheckResult {

    private final boolean success;
    private final String error;
    private final String playerName;
    private final String platform;
    private final String banStatus;
    private final String banType;
    private final Integer matchCount;
    private final String siteUUID;
    private final Integer survivalLevel;
    private final Integer survivalTier;
    private final Integer totalLevel;
    private final Integer totalMatches;

    private BanCheckResult(boolean success, String error, String playerName, String platform,
                           String banStatus, String banType, Integer matchCount, String siteUUID,
                           Integer survivalLevel, Integer survivalTier, Integer totalLevel,
                           Integer totalMatches) {
        this.success = success;
        this.error = error;
        this.playerName = playerName;
        this.platform = platform;
        this.banStatus = banStatus;
        this.banType = banType;
        this.matchCount = matchCount;
        this.siteUUID = siteUUID;
        this.survivalLevel = survivalLevel;
        this.survivalTier = survivalTier;
        this.totalLevel = totalLevel;
        this.totalMatches = totalMatches;
    }

    /**
     * 从 pubg.hk 的原始响应解析。响应形如
     * {@code {"success":true,"data":{"siteUUID":"...","playerName":"...","platform":"steam",
     * "banStatus":"未封禁","banType":"Innocent","matchCount":1080,"survivalLevel":109,"survivalTier":5,
     * "totalLevel":2109,"totalMatches":8496,"matchIDs":[...]}}}。
     */
    public static BanCheckResult from(JsonNode body) {
        if (body == null || !body.path("success").asBoolean(false)) {
            String error = body == null ? "空响应"
                    : body.path("error").asText(body.path("msg").asText("查询失败"));
            return new BanCheckResult(false, error, null, null, null, null, null, null, null, null, null, null);
        }
        JsonNode data = body.path("data");
        return new BanCheckResult(true, null,
                textOrNull(data, "playerName"),
                textOrNull(data, "platform"),
                textOrNull(data, "banStatus"),
                textOrNull(data, "banType"),
                intOrNull(data, "matchCount"),
                textOrNull(data, "siteUUID"),
                intOrNull(data, "survivalLevel"),
                intOrNull(data, "survivalTier"),
                intOrNull(data, "totalLevel"),
                intOrNull(data, "totalMatches"));
    }

    /** 构造一个失败结果，错误信息如实带上游/网络的原文。 */
    public static BanCheckResult failure(String error) {
        return new BanCheckResult(false, error, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 等级的展示形式：段位 + 段内级，如「5段109级」。
     * 段位或段内级缺失时回退到上游的 {@code totalLevel} 纯数字；都没有则 null。
     */
    public String getLevelText() {
        if (survivalTier != null && survivalLevel != null) {
            return survivalTier + "段" + survivalLevel + "级";
        }
        return totalLevel == null ? null : String.valueOf(totalLevel);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : null;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getError() {
        return error;
    }

    public String getPlayerName() {
        return playerName;
    }

    /** 平台，如 steam / kakao / console。取上游响应的 {@code data.platform}，未返回时为 null。 */
    public String getPlatform() {
        return platform;
    }

    public String getBanStatus() {
        return banStatus;
    }

    public String getBanType() {
        return banType;
    }

    /** 对局数（pubg.hk 的 matchCount），与账号表的「总场次」不是一回事。 */
    public Integer getMatchCount() {
        return matchCount;
    }

    public String getSiteUUID() {
        return siteUUID;
    }

    public Integer getSurvivalLevel() {
        return survivalLevel;
    }

    public Integer getSurvivalTier() {
        return survivalTier;
    }

    public Integer getTotalLevel() {
        return totalLevel;
    }

    /** 总场次（pubg.hk 的 totalMatches），即账号表的「总场次」。 */
    public Integer getTotalMatches() {
        return totalMatches;
    }
}