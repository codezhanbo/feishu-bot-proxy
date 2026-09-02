package com.example.feishuproxy.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * pubg.hk 封禁查询的一次结果。只挑后台页面用得上的字段，舍弃了体积很大的 {@code matchIDs} 数组。
 * <p>
 * 成功与失败共用同一个类型：失败时 {@link #isSuccess()} 为 false，其余字段全为 null，
 * 错误原因放在 {@link #getError()}。这样客户端无需用异常或 null 来表达「查无此人」这类上游结果。
 */
public final class BanCheckResult {

    private final boolean success;
    private final String error;
    private final String playerName;
    private final String banStatus;
    private final String banType;
    private final Integer matchCount;
    private final String siteUUID;

    private BanCheckResult(boolean success, String error, String playerName, String banStatus,
                           String banType, Integer matchCount, String siteUUID) {
        this.success = success;
        this.error = error;
        this.playerName = playerName;
        this.banStatus = banStatus;
        this.banType = banType;
        this.matchCount = matchCount;
        this.siteUUID = siteUUID;
    }

    /**
     * 从 pubg.hk 的原始响应解析。响应形如
     * {@code {"success":true,"data":{"siteUUID":"...","playerName":"...","banStatus":"未封禁",
     * "banType":"Innocent","matchCount":1078,"matchIDs":[...]}}}。
     */
    public static BanCheckResult from(JsonNode body) {
        if (body == null || !body.path("success").asBoolean(false)) {
            String error = body == null ? "空响应"
                    : body.path("error").asText(body.path("msg").asText("查询失败"));
            return new BanCheckResult(false, error, null, null, null, null, null);
        }
        JsonNode data = body.path("data");
        return new BanCheckResult(true, null,
                textOrNull(data, "playerName"),
                textOrNull(data, "banStatus"),
                textOrNull(data, "banType"),
                data.path("matchCount").isNumber() ? data.path("matchCount").asInt() : null,
                textOrNull(data, "siteUUID"));
    }

    /** 构造一个失败结果，错误信息如实带上游/网络的原文。 */
    public static BanCheckResult failure(String error) {
        return new BanCheckResult(false, error, null, null, null, null, null);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
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

    public String getBanStatus() {
        return banStatus;
    }

    public String getBanType() {
        return banType;
    }

    public Integer getMatchCount() {
        return matchCount;
    }

    public String getSiteUUID() {
        return siteUUID;
    }
}
