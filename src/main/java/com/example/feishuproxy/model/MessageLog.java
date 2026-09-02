package com.example.feishuproxy.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 一条已落库的入站消息：对应 {@code message_log} 的一行、GET /admin/logs 的一条记录。
 * <p>
 * {@link #getBotKeys()} 和 {@link #getResults()} 保持复数形态，因为广播功能移除之前写入的行
 * 可能指向多个目标。
 */
public class MessageLog {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final long id;
    private final long epochMillis;
    /** 落库时间的可读形式，就是 {@link #getTime()} 持久化到 {@code create_datetime} 列的值；旧行该列为 NULL。 */
    private final String createDatetime;
    private final String botKeys;
    private final String msgType;
    private final String title;
    private final String textPreview;
    private final String body;
    private final int bodyBytes;
    private final String clientIp;
    private final boolean success;
    private final int code;
    private final String msg;
    private final JsonNode results;
    private final GameStats stats;

    public MessageLog(long id, long epochMillis, String createDatetime, String botKeys, String msgType,
                      String title, String textPreview, String body, int bodyBytes, String clientIp,
                      boolean success, int code, String msg, JsonNode results, GameStats stats) {
        this.id = id;
        this.epochMillis = epochMillis;
        this.createDatetime = createDatetime;
        this.botKeys = botKeys;
        this.msgType = msgType;
        this.title = title;
        this.textPreview = textPreview;
        this.body = body;
        this.bodyBytes = bodyBytes;
        this.clientIp = clientIp;
        this.success = success;
        this.code = code;
        this.msg = msg;
        this.results = results;
        this.stats = stats;
    }

    public long getId() {
        return id;
    }

    public String getTime() {
        return formatDateTime(epochMillis);
    }

    public long getEpochMillis() {
        return epochMillis;
    }

    /** 与 {@link #getTime()} 同值的可读时间，作为 {@code create_datetime} 列独立持久化；旧行该列为 NULL。 */
    public String getCreateDatetime() {
        return createDatetime;
    }

    /** epoch 毫秒 → 可读时间串。{@link #getTime()} 和落库的 {@code create_datetime} 列都走这里，保证同一格式。 */
    public static String formatDateTime(long epochMillis) {
        return FORMATTER.format(Instant.ofEpochMilli(epochMillis));
    }

    /** 目标群组。对于「一个请求可到达多个目标」时代遗留的行，用逗号拼接。 */
    public String getBotKeys() {
        return botKeys;
    }

    public String getMsgType() {
        return msgType;
    }

    public String getTitle() {
        return title;
    }

    public String getTextPreview() {
        return textPreview;
    }

    /** 调用方自己的 JSON，原样保留——不是发往上游的那份加签后的报文。 */
    public String getBody() {
        return body;
    }

    /** 原始请求的长度，可能超过 {@link #getBody()} 实际保留的长度。 */
    public int getBodyBytes() {
        return bodyBytes;
    }

    public String getClientIp() {
        return clientIp;
    }

    /** 仅当每个目标机器人都接受了该消息时才为 true。 */
    public boolean isSuccess() {
        return success;
    }

    /** 就是回答给调用方的那个 {@code code}。 */
    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }

    /** 每个机器人的结果：{@code [{botKey,success,code,msg,attempts,costMs}, ...]}。 */
    public JsonNode getResults() {
        return results;
    }

    /** 战报里解析出来的游戏数值。这条消息不是战报时为 null。 */
    public GameStats getStats() {
        return stats;
    }
}
