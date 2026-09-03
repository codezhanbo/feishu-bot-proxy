package com.example.feishuproxy.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.feishuproxy.store.typehandler.BooleanToIntTypeHandler;

/**
 * {@code bot} 表的实体。与 {@code FeishuProperties.Bot}（配置/视图类）分离：这里 {@code keywords}
 * 存逗号连接的原始字符串，由 {@code BotRepository} 负责与 {@code List<String>} 互转。
 */
@TableName("bot")
public class BotEntity {

    @TableId(type = IdType.INPUT)
    private String botKey;
    private String webhook;
    private String secret;
    @TableField(typeHandler = BooleanToIntTypeHandler.class)
    private boolean enabled;
    private String keywords;
    private long createdAt;
    private long updatedAt;

    public String getBotKey() {
        return botKey;
    }

    public void setBotKey(String botKey) {
        this.botKey = botKey;
    }

    public String getWebhook() {
        return webhook;
    }

    public void setWebhook(String webhook) {
        this.webhook = webhook;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeywords() {
        return keywords;
    }

    public void setKeywords(String keywords) {
        this.keywords = keywords;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
