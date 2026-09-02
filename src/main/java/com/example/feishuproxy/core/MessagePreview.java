package com.example.feishuproxy.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Map;

/**
 * 从飞书消息报文里抽取一个人类可读的标题和一行文本预览。
 * <p>
 * 这两列让落库的消息变得可浏览——没有它们，{@code /admin/logs} 就是一整面原始 JSON，
 * 要找昨天的报告只能逐条读正文。完整正文仍然原样存储，这纯粹是为了方便浏览而做的投影。
 * <p>
 * 每个方法都容忍 null 或意外的节点，返回 null 而不是抛异常：因为调用方还要记录那些
 * 在解析之前就被拒掉的请求。
 */
public final class MessagePreview {

    /** 是码点而非字符——emoji 是代理对，绝不能从中间劈开。 */
    private static final int MAX_PREVIEW_CODE_POINTS = 200;

    private static final int MAX_DEPTH = 12;

    private MessagePreview() {
    }

    /** 消息自身的标题（当其类型有标题时）。text、image 等类型则为 null。 */
    public static String title(JsonNode body) {
        if (body == null || !body.isObject()) {
            return null;
        }
        String msgType = body.path("msg_type").asText("");
        if ("post".equals(msgType)) {
            // content.post 按语言区域（zh_cn、en_us……）作键。取第一个有标题的，而不是写死 zh_cn——
            // 因为同一个中转服务会转发两种语言。
            for (JsonNode locale : body.path("content").path("post")) {
                String title = clean(locale.path("title").asText(null));
                if (title != null) {
                    return truncate(title);
                }
            }
            return null;
        }
        if ("interactive".equals(msgType)) {
            return truncate(clean(body.path("card").path("header").path("title")
                    .path("content").asText(null)));
        }
        return null;
    }

    /** 消息正文压平、折叠空白后的一小段摘要，长度受控以便展示。 */
    public static String preview(JsonNode body) {
        if (body == null || !body.isObject()) {
            return null;
        }
        String msgType = body.path("msg_type").asText("");
        JsonNode content = body.path("content");

        if ("text".equals(msgType)) {
            return truncate(clean(content.path("text").asText(null)));
        }
        if ("image".equals(msgType)) {
            return truncate(clean(content.path("image_key").asText(null)));
        }
        if ("share_chat".equals(msgType)) {
            return truncate(clean(content.path("share_chat_id").asText(null)));
        }
        if ("post".equals(msgType)) {
            StringBuilder out = new StringBuilder();
            for (JsonNode locale : content.path("post")) {
                appendPostBody(locale.path("content"), out);
            }
            String post = clean(out.toString());
            if (post != null) {
                return truncate(post);
            }
        }

        // 用于交互卡片以及飞书日后新增的任何类型的兜底。故意放在最后，这样上面的显式处理
        // 绝不会被某个游离的 text 节点盖过。
        StringBuilder out = new StringBuilder();
        collectText(body, out, 0);
        return truncate(clean(out.toString()));
    }

    /** {@code content.post.<locale>.content} 是段落数组，每个段落又是 span 数组。 */
    private static void appendPostBody(JsonNode paragraphs, StringBuilder out) {
        for (JsonNode paragraph : paragraphs) {
            for (JsonNode span : paragraph) {
                append(out, spanText(span));
            }
            append(out, " ");
        }
    }

    /** img、media、emotion 这几类 span 根本不带文本，所以这里返回 null。 */
    private static String spanText(JsonNode span) {
        String tag = span.path("tag").asText("");
        if ("text".equals(tag) || "a".equals(tag)) {
            return span.path("text").asText(null);
        }
        if ("at".equals(tag)) {
            return span.path("user_name").asText(null);
        }
        return null;
    }

    private static void collectText(JsonNode node, StringBuilder out, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectText(child, out, depth + 1);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            String key = field.getKey();
            if (value.isTextual() && ("text".equals(key) || "content".equals(key))) {
                append(out, value.asText());
            } else {
                collectText(value, out, depth + 1);
            }
        }
    }

    private static void append(StringBuilder out, String text) {
        if (text != null && !text.isEmpty()) {
            out.append(text);
        }
    }

    /** 折叠换行和连续空格，让预览保持在一行内。为空时返回 null。 */
    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        String collapsed = text.replaceAll("[\\s\\u00A0]+", " ").trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints <= MAX_PREVIEW_CODE_POINTS) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, MAX_PREVIEW_CODE_POINTS));
    }
}
