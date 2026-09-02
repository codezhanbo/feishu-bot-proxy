package com.example.feishuproxy.model;

/**
 * 把一个请求中转到单个机器人的结果。
 * <p>
 * 当 {@link #getFeishuBody()} 非 null 时，说明调用确实到达了飞书，单机器人调用方拿到的就是
 * 那个响应体的原样内容；否则说明失败是中转服务自身产生的。
 */
public class SendResult {

    private final String botKey;
    private final boolean success;
    private final int code;
    private final String msg;
    private final int httpStatus;
    private final String feishuBody;
    private final int attempts;
    private final long costMs;

    private SendResult(String botKey, boolean success, int code, String msg,
                       int httpStatus, String feishuBody, int attempts, long costMs) {
        this.botKey = botKey;
        this.success = success;
        this.code = code;
        this.msg = msg;
        this.httpStatus = httpStatus;
        this.feishuBody = feishuBody;
        this.attempts = attempts;
        this.costMs = costMs;
    }

    /** 请求到达了飞书；{@code body} 原样返回给调用方。 */
    public static SendResult fromFeishu(String botKey, int httpStatus, String body,
                                        FeishuResponse parsed, int attempts, long costMs) {
        return new SendResult(botKey, parsed.isSuccess() && httpStatus < 400, parsed.getCode(),
                parsed.getMsg(), httpStatus, body, attempts, costMs);
    }

    /** 中转服务在调用飞书之前（或干脆没调飞书）就拒掉了这个请求。 */
    public static SendResult localError(String botKey, int httpStatus, int code, String msg) {
        return new SendResult(botKey, false, code, msg, httpStatus, null, 0, 0L);
    }

    /** 重试多次后飞书仍然不可达。 */
    public static SendResult upstreamError(String botKey, String msg, int attempts, long costMs) {
        return new SendResult(botKey, false, 50200, "upstream error: " + msg, 502, null, attempts, costMs);
    }

    public boolean isPassthrough() {
        return feishuBody != null;
    }

    public String getBotKey() {
        return botKey;
    }

    public boolean isSuccess() {
        return success;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getFeishuBody() {
        return feishuBody;
    }

    public int getAttempts() {
        return attempts;
    }

    public long getCostMs() {
        return costMs;
    }
}
