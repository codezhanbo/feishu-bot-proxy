package com.example.feishuproxy.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code message_log} 表的实体，只用于 MyBatis-Plus 的读写。
 * <p>
 * 与 {@code model.MessageLog}（不可变 view model，含解析后的 {@code JsonNode results} 与
 * {@code GameStats stats}）不同，这里原样对应 18 列：{@code results} 存 JSON 文本字符串、
 * {@code success} 存 INTEGER 0/1（由仓储层转成 boolean）。字段名与列名走默认的 camel↔snake 映射。
 */
@TableName("message_log")
public class MessageLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private long createdAt;
    private String createDatetime;
    private String botKeys;
    private String msgType;
    private String title;
    private String textPreview;
    private String body;
    private int bodyBytes;
    private String clientIp;
    private Integer success;
    private int code;
    private String msg;
    private String results;
    private String statDate;
    private Integer survivalLevel;
    private Long expGained;
    private Long bpGained;
    private String duration;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreateDatetime() {
        return createDatetime;
    }

    public void setCreateDatetime(String createDatetime) {
        this.createDatetime = createDatetime;
    }

    public String getBotKeys() {
        return botKeys;
    }

    public void setBotKeys(String botKeys) {
        this.botKeys = botKeys;
    }

    public String getMsgType() {
        return msgType;
    }

    public void setMsgType(String msgType) {
        this.msgType = msgType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTextPreview() {
        return textPreview;
    }

    public void setTextPreview(String textPreview) {
        this.textPreview = textPreview;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public int getBodyBytes() {
        return bodyBytes;
    }

    public void setBodyBytes(int bodyBytes) {
        this.bodyBytes = bodyBytes;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public Integer getSuccess() {
        return success;
    }

    public void setSuccess(Integer success) {
        this.success = success;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public String getResults() {
        return results;
    }

    public void setResults(String results) {
        this.results = results;
    }

    public String getStatDate() {
        return statDate;
    }

    public void setStatDate(String statDate) {
        this.statDate = statDate;
    }

    public Integer getSurvivalLevel() {
        return survivalLevel;
    }

    public void setSurvivalLevel(Integer survivalLevel) {
        this.survivalLevel = survivalLevel;
    }

    public Long getExpGained() {
        return expGained;
    }

    public void setExpGained(Long expGained) {
        this.expGained = expGained;
    }

    public Long getBpGained() {
        return bpGained;
    }

    public void setBpGained(Long bpGained) {
        this.bpGained = bpGained;
    }

    public String getDuration() {
        return duration;
    }

    public void setDuration(String duration) {
        this.duration = duration;
    }
}
