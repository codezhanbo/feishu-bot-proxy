package com.example.feishuproxy.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 飞书对 webhook 调用的回复。v2 的 hook 返回 {@code {"code":0,"msg":"success"}}，
 * 而一些较旧的响应使用 {@code StatusCode}/{@code StatusMessage}；两种写法都接受。
 */
public class FeishuResponse {

    /** 当响应体缺失或根本不是 JSON 时使用。 */
    public static final int UNKNOWN_CODE = -1;

    private final int code;
    private final String msg;

    private FeishuResponse(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public static FeishuResponse parse(ObjectMapper mapper, String body) {
        if (body == null || body.trim().isEmpty()) {
            return new FeishuResponse(UNKNOWN_CODE, "");
        }
        try {
            JsonNode node = mapper.readTree(body);
            if (node == null || !node.isObject()) {
                return new FeishuResponse(UNKNOWN_CODE, "");
            }
            int code = node.has("code")
                    ? node.path("code").asInt(UNKNOWN_CODE)
                    : node.path("StatusCode").asInt(UNKNOWN_CODE);
            String msg = node.has("msg")
                    ? node.path("msg").asText("")
                    : node.path("StatusMessage").asText("");
            return new FeishuResponse(code, msg);
        } catch (Exception e) {
            return new FeishuResponse(UNKNOWN_CODE, "");
        }
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }

    public boolean isSuccess() {
        return code == 0;
    }
}
