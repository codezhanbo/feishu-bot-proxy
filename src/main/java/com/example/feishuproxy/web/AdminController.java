package com.example.feishuproxy.web;

import com.example.feishuproxy.config.FeishuProperties;
import com.example.feishuproxy.core.BotRegistry;
import com.example.feishuproxy.core.FeishuSender;
import com.example.feishuproxy.model.MessageLog;
import com.example.feishuproxy.model.SendResult;
import com.example.feishuproxy.store.MessageLogRepository;
import com.example.feishuproxy.store.StatsCollector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 运维相关端点：配置了哪些机器人、发送过什么，以及冒烟测试单个机器人的入口。 */
@RestController
public class AdminController {

    private final BotRegistry registry;
    private final FeishuSender sender;
    private final MessageLogRepository messageLog;
    private final StatsCollector stats;
    private final ObjectMapper objectMapper;

    public AdminController(BotRegistry registry, FeishuSender sender, MessageLogRepository messageLog,
                           StatsCollector stats, ObjectMapper objectMapper) {
        this.registry = registry;
        this.sender = sender;
        this.messageLog = messageLog;
        this.stats = stats;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("bots", registry.all().size());
        return JsonResponses.ok(objectMapper, body);
    }

    /** webhook URL 已打码——它们是凭据。 */
    @GetMapping("/admin/bots")
    public ResponseEntity<String> bots() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, FeishuProperties.Bot> entry : registry.all().entrySet()) {
            FeishuProperties.Bot bot = entry.getValue();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("webhook", BotRegistry.maskWebhook(bot.getWebhook()));
            info.put("enabled", bot.isEnabled());
            info.put("signed", bot.hasSecret());
            info.put("keywords", bot.getKeywords());
            out.put(entry.getKey(), info);
        }
        return JsonResponses.ok(objectMapper, out);
    }

    /**
     * 落库的消息，最新在前。与上面的计数器不同，这些数据重启后仍在，所以
     * {@code offset} 是用来翻历史页的，而不只是瞄一眼末尾。
     */
    @GetMapping("/admin/logs")
    public ResponseEntity<String> logs(@RequestParam(value = "botKey", required = false) String botKey,
                                       @RequestParam(value = "success", required = false) Boolean success,
                                       @RequestParam(value = "limit", defaultValue = "50") int limit,
                                       @RequestParam(value = "offset", defaultValue = "0") int offset) {
        if (!messageLog.isEnabled()) {
            return JsonResponses.error(objectMapper, 503, 50301, "message store unavailable");
        }
        List<MessageLog> records = messageLog.query(botKey, success, limit, offset);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", records.size());
        out.put("total", messageLog.total());
        out.put("offset", Math.max(0, offset));
        out.put("maxLimit", MessageLogRepository.MAX_PAGE);
        out.put("note", "persisted to sqlite, one row per request, never pruned");
        out.put("records", records);
        return JsonResponses.ok(objectMapper, out);
    }

    @GetMapping("/admin/stats")
    public ResponseEntity<String> stats() {
        return JsonResponses.ok(objectMapper, stats.snapshot());
    }

    /**
     * 走完整条流水线发送一条真实文本消息。如果该群组配置了关键词，
     * 就带上第一个关键词，好让飞书的关键词校验通过。
     */
    @PostMapping("/admin/test/{botKey}")
    public ResponseEntity<String> test(@PathVariable("botKey") String botKey, HttpServletRequest request) {
        FeishuProperties.Bot bot = registry.get(botKey);
        if (bot == null) {
            return JsonResponses.error(objectMapper, 404, 40401, "unknown botKey: " + botKey);
        }
        if (!bot.isEnabled()) {
            return JsonResponses.error(objectMapper, 403, 40301, "bot disabled: " + botKey);
        }

        String prefix = bot.getKeywords().isEmpty() ? "" : bot.getKeywords().get(0) + " ";
        String text = prefix + "[feishu-bot-proxy] test message for " + botKey;

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("msg_type", "text");
        payload.putObject("content").put("text", text);
        byte[] body = JsonResponses.write(objectMapper, payload).getBytes(StandardCharsets.UTF_8);

        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(body);
        } catch (Exception e) {
            return JsonResponses.error(objectMapper, 500, 50000, "internal error");
        }

        SendResult result = sender.send(botKey, bot, body, parsed, "text", request.getRemoteAddr());

        // 和其他消息一样留档，这样冒烟测试也会出现在 /admin/logs 里。
        messageLog.record(Collections.singletonList(botKey), body, parsed, request.getRemoteAddr(),
                result.getCode(), result.getMsg(), Collections.singletonList(result));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("botKey", botKey);
        out.put("success", result.isSuccess());
        out.put("code", result.getCode());
        out.put("msg", result.getMsg());
        out.put("attempts", result.getAttempts());
        out.put("costMs", result.getCostMs());
        out.put("sentText", text);
        return JsonResponses.ok(objectMapper, out);
    }
}
