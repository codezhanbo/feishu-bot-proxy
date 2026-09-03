package com.example.feishuproxy.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code app_setting} 表的实体：应用级键值设置（目前只有 {@code default-bot} 一项）。
 */
@TableName("app_setting")
public class SettingEntity {

    @TableId(type = IdType.INPUT)
    private String settingKey;
    private String settingValue;

    public String getSettingKey() {
        return settingKey;
    }

    public void setSettingKey(String settingKey) {
        this.settingKey = settingKey;
    }

    public String getSettingValue() {
        return settingValue;
    }

    public void setSettingValue(String settingValue) {
        this.settingValue = settingValue;
    }
}
