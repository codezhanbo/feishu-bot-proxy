package com.example.feishuproxy.web;

import com.example.feishuproxy.config.FeishuProperties;
import com.example.feishuproxy.core.BotRegistry;
import com.example.feishuproxy.core.FeishuSender;
import com.example.feishuproxy.model.SendResult;
import com.example.feishuproxy.store.MessageLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * 飞书 webhook URL 的即插即用替代品。
 * <p>
 * 调用方继续提交飞书原生报文，只有主机和末尾路径变了：
 * {@code https://open.feishu.cn/open-apis/bot/v2/hook/xxx} 变成 {@code /webhook/dev-group}。
 * 一个请求只面向一个群组，响应则与飞书答复逐字节一致。
 * <p>
 * 每个带请求体的请求都会被持久化——无论结果如何都写一行，包括那些到达飞书之前就被拒掉的。
 * 写入发生在响应构造完成之后，所以存储问题能改变「记录了什么」，却永远改变不了「答复了什么」。
 */
@RestController
public class WebhookController {

    private final BotRegistry registry;
    private final FeishuSender sender;
    private final FeishuProperties properties;
    private final MessageLogRepository messageLog;
    private final ObjectMapper objectMapper;

    public WebhookController(BotRegistry registry, FeishuSender sender, FeishuProperties properties,
                             MessageLogRepository messageLog, ObjectMapper objectMapper) {
        this.registry = registry;
        this.sender = sender;
        this.properties = properties;
        this.messageLog = messageLog;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/webhook/{botKey}")
    public ResponseEntity<String> forward(@PathVariable("botKey") String botKey,
                                          @RequestBody(required = false) byte[] body,
                                          HttpServletRequest request) {
        return relay(BotRegistry.normalizeBotKey(botKey), body, request);
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> forwardToDefault(@RequestBody(required = false) byte[] body,
                                                   HttpServletRequest request) {
        String defaultBot = BotRegistry.normalizeBotKey(registry.getDefaultBot());
        if (defaultBot.isEmpty()) {
            return reject("", body, null, request, 400, 40002, "no default bot configured");
        }
        return relay(defaultBot, body, request);
    }

    private ResponseEntity<String> relay(String botKey, byte[] body, HttpServletRequest request) {
        if (botKey.isEmpty()) {
            return reject(botKey, body, null, request, 400, 40002, "empty bot key");
        }
        if (botKey.indexOf(',') >= 0) {
            // 逗号曾经是广播的分隔符。广播移除后这种 key 反正都是 404，但如果放它落库，
            // bot_keys 列（同样以逗号拼接）就会多出一行假的多目标记录，
            // 把 /admin/logs?botKey=... 的过滤结果污染掉。所以在这里就拒掉，并且不带目标落档。
            return reject("", body, null, request, 400, 40002, "bot key must not contain ',': " + botKey);
        }
        if (body == null || body.length == 0) {
            // 没有可转发的，也没有值得留档的。
            return JsonResponses.error(objectMapper, 400, 40001, "empty request body");
        }
        if (body.length > properties.getMaxBodyBytes()) {
            return reject(botKey, body, null, request, 413, 41301,
                    "body exceeds " + properties.getMaxBodyBytes() + " bytes");
        }

        // 只解析一次，也只为三件事：读 msg_type 供日志用、抽取可读的标题和预览供落库用、
        // 以及加签。发往上游的字节仍是调用方自己的，除非该机器人配置了密钥。
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(body);
        } catch (IOException e) {
            parsed = null;
        }
        if (parsed == null || !parsed.isObject()) {
            return reject(botKey, body, null, request, 400, 40001, "invalid json body");
        }

        String msgType = parsed.path("msg_type").asText("unknown");
        String clientIp = clientIp(request);
        SendResult result = sendTo(botKey, body, parsed, msgType, clientIp);

        // 原样返回飞书的答复，这样以前直连飞书的调用方一行代码都不用改。
        ResponseEntity<String> response = result.isPassthrough()
                ? ResponseEntity.status(result.getHttpStatus())
                    .contentType(JsonResponses.JSON_UTF8).body(result.getFeishuBody())
                : JsonResponses.error(objectMapper, result.getHttpStatus(), result.getCode(), result.getMsg());

        // 响应已构造完成；留档无法再改变它。
        messageLog.record(Collections.singletonList(botKey), body, parsed, clientIp,
                result.getCode(), result.getMsg(), Collections.singletonList(result));
        return response;
    }

    /** 发送前就被拒掉：先答复，再以空的 results 数组落一行。 */
    private ResponseEntity<String> reject(String botKey, byte[] body, JsonNode parsed,
                                          HttpServletRequest request, int status, int code, String msg) {
        ResponseEntity<String> response = JsonResponses.error(objectMapper, status, code, msg);
        List<String> targets = botKey.isEmpty()
                ? Collections.<String>emptyList() : Collections.singletonList(botKey);
        messageLog.record(targets, body, parsed, clientIp(request), code, msg,
                Collections.<SendResult>emptyList());
        return response;
    }

    private SendResult sendTo(String botKey, byte[] body, JsonNode parsed, String msgType, String clientIp) {
        FeishuProperties.Bot bot = registry.get(botKey);
        if (bot == null) {
            return SendResult.localError(botKey, 404, 40401, "unknown botKey: " + botKey);
        }
        if (!bot.isEnabled()) {
            return SendResult.localError(botKey, 403, 40301, "bot disabled: " + botKey);
        }
        return sender.send(botKey, bot, body, parsed, msgType, clientIp);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.trim().isEmpty()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.trim().isEmpty()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
