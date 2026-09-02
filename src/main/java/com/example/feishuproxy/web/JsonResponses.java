package com.example.feishuproxy.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** 中转服务自身（非透传）应答的共用 JSON 响应工具。 */
final class JsonResponses {

    static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");

    private JsonResponses() {
    }

    static ResponseEntity<String> error(ObjectMapper mapper, int httpStatus, int code, String msg) {
        ObjectNode node = mapper.createObjectNode();
        node.put("code", code);
        node.put("msg", msg);
        return ResponseEntity.status(httpStatus).contentType(JSON_UTF8).body(write(mapper, node));
    }

    static ResponseEntity<String> ok(ObjectMapper mapper, Object value) {
        try {
            return ResponseEntity.ok().contentType(JSON_UTF8).body(mapper.writeValueAsString(value));
        } catch (Exception e) {
            return error(mapper, 500, 50000, "internal error");
        }
    }

    static String write(ObjectMapper mapper, JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            // createObjectNode() 的输出总是可序列化的；这只是双重保险。
            return "{\"code\":50000,\"msg\":\"internal error\"}";
        }
    }
}
