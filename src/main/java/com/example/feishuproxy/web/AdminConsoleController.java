package com.example.feishuproxy.web;

import com.example.feishuproxy.config.AdminSessionInterceptor;
import com.example.feishuproxy.config.FeishuProperties;
import com.example.feishuproxy.core.BotRegistry;
import com.example.feishuproxy.core.FeishuSender;
import com.example.feishuproxy.model.MessageLog;
import com.example.feishuproxy.model.SendResult;
import com.example.feishuproxy.store.BotRepository;
import com.example.feishuproxy.store.MessageLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台管理界面的后端：登录、登出、会话查询、消息留档查询、机器人配置与默认机器人。
 * <p>
 * 鉴权走 {@link AdminSessionInterceptor}（会话 cookie），只保护页面与 {@code /console/**} 下的接口；
 * 登录 / 登出 / 会话这三个接口本身不拦，否则就没法登录了。
 */
@RestController
public class AdminConsoleController {

    private final FeishuProperties properties;
    private final MessageLogRepository messageLog;
    private final BotRepository bots;
    private final BotRegistry registry;
    private final FeishuSender sender;
    private final ObjectMapper objectMapper;

    public AdminConsoleController(FeishuProperties properties, MessageLogRepository messageLog,
                                  BotRepository bots, BotRegistry registry, FeishuSender sender,
                                  ObjectMapper objectMapper) {
        this.properties = properties;
        this.messageLog = messageLog;
        this.bots = bots;
        this.registry = registry;
        this.sender = sender;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/console/login")
    public ResponseEntity<String> login(HttpServletRequest request,
                                        @RequestParam String username,
                                        @RequestParam String password) {
        String expectedUser = properties.getAdmin().getUsername();
        String expectedPass = properties.getAdmin().getPassword();
        if (expectedPass == null || expectedPass.isEmpty()) {
            return JsonResponses.error(objectMapper, 500, 50003, "admin password not configured");
        }

        // 用户名非机密，用 equals；密码用常量时间比较，和 X-Api-Token 一致。
        boolean userOk = expectedUser != null && expectedUser.equals(username);
        boolean passOk = MessageDigest.isEqual(
                expectedPass.getBytes(StandardCharsets.UTF_8),
                (password == null ? "" : password).getBytes(StandardCharsets.UTF_8));
        if (!userOk || !passOk) {
            return JsonResponses.error(objectMapper, 401, 40100, "用户名或密码错误");
        }

        HttpSession session = request.getSession(true);
        session.setAttribute(AdminSessionInterceptor.AUTH_ATTRIBUTE, Boolean.TRUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", true);
        return JsonResponses.ok(objectMapper, body);
    }

    @PostMapping("/console/logout")
    public ResponseEntity<String> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", false);
        return JsonResponses.ok(objectMapper, body);
    }

    /** 登录页和查询页都用它判断当前是否已登录。 */
    @GetMapping("/console/session")
    public ResponseEntity<String> session(HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", isAuthenticated(request));
        return JsonResponses.ok(objectMapper, body);
    }

    /**
     * 查询消息留档。与 {@code /admin/logs} 返回同一形状，但多了 keyword / from / to 三个过滤，
     * 且走会话鉴权而非 {@code X-Api-Token}。
     */
    @GetMapping("/console/logs")
    public ResponseEntity<String> logs(@RequestParam(required = false) String botKey,
                                       @RequestParam(required = false) Boolean success,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Long from,
                                       @RequestParam(required = false) Long to,
                                       @RequestParam(defaultValue = "20") int limit,
                                       @RequestParam(defaultValue = "0") int offset) {
        if (!messageLog.isEnabled()) {
            return JsonResponses.error(objectMapper, 503, 50301, "message store unavailable");
        }
        List<MessageLog> records = messageLog.query(botKey, success, keyword, from, to, limit, offset);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", records.size());
        out.put("total", messageLog.total());
        out.put("offset", Math.max(0, offset));
        out.put("maxLimit", MessageLogRepository.MAX_PAGE);
        out.put("note", "persisted to postgres, one row per request, never pruned");
        out.put("records", records);
        return JsonResponses.ok(objectMapper, out);
    }

    /** 全局设置。目前只有 default-bot 一项。 */
    @GetMapping("/console/settings")
    public ResponseEntity<String> settings() {
        String defaultBot = bots.getDefaultBot();
        if (defaultBot == null) {
            return JsonResponses.error(objectMapper, 503, 50301, "bot store unavailable");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("defaultBot", defaultBot);
        return JsonResponses.ok(objectMapper, out);
    }

    /** 设置默认机器人。空串清除默认。 */
    @PutMapping("/console/settings")
    public ResponseEntity<String> updateSettings(@RequestBody JsonNode body) {
        String defaultBot = body.path("defaultBot").asText("");
        try {
            bots.setDefaultBot(defaultBot);
            registry.reload();
        } catch (IllegalStateException e) {
            return JsonResponses.error(objectMapper, 503, 50301, "bot store unavailable");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("defaultBot", defaultBot.trim());
        return JsonResponses.ok(objectMapper, out);
    }

    /** 机器人列表。webhook 回传全文（供编辑框回填），另给一份脱敏值供列表展示；secret 绝不回传。 */
    @GetMapping("/console/bots")
    public ResponseEntity<String> listBots() {
        Map<String, FeishuProperties.Bot> all = bots.findAll();
        if (all == null) {
            return JsonResponses.error(objectMapper, 503, 50301, "bot store unavailable");
        }
        ArrayNode items = objectMapper.createArrayNode();
        for (Map.Entry<String, FeishuProperties.Bot> entry : all.entrySet()) {
            FeishuProperties.Bot bot = entry.getValue();
            items.addObject()
                    .put("botKey", entry.getKey())
                    .put("webhook", bot.getWebhook())
                    .put("webhookMasked", BotRegistry.maskWebhook(bot.getWebhook()))
                    .put("enabled", bot.isEnabled())
                    .put("hasSecret", bot.hasSecret())
                    .set("keywords", keywordsNode(bot.getKeywords()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bots", items);
        return JsonResponses.ok(objectMapper, out);
    }

    @PostMapping("/console/bots")
    public ResponseEntity<String> createBot(@RequestBody JsonNode body) {
        String botKey = normalizeBotKey(body.path("botKey").asText(""));
        if (botKey.isEmpty()) {
            return JsonResponses.error(objectMapper, 400, 40002, "botKey is required");
        }
        if (botKey.indexOf(',') >= 0) {
            return JsonResponses.error(objectMapper, 400, 40002, "bot key must not contain ',': " + botKey);
        }
        String webhook = body.path("webhook").asText("");
        if (webhook.trim().isEmpty()) {
            return JsonResponses.error(objectMapper, 400, 40001, "webhook is required");
        }
        try {
            if (bots.find(botKey) != null) {
                return JsonResponses.error(objectMapper, 409, 40901, "botKey already exists: " + botKey);
            }
            bots.insert(botKey, buildBot(body, webhook, null));
            registry.reload();
        } catch (IllegalStateException e) {
            return JsonResponses.error(objectMapper, 503, 50301, "bot store unavailable");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("botKey", botKey);
        return JsonResponses.ok(objectMapper, out);
    }

    @PutMapping("/console/bots/{botKey}")
    public ResponseEntity<String> updateBot(@PathVariable("botKey") String botKey, @RequestBody JsonNode body) {
        botKey = normalizeBotKey(botKey);
        String webhook = body.path("webhook").asText("");
        if (webhook.trim().isEmpty()) {
            return JsonResponses.error(objectMapper, 400, 40001, "webhook is required");
        }
        try {
            FeishuProperties.Bot existing = bots.find(botKey);
            if (existing == null) {
                return JsonResponses.error(objectMapper, 404, 40401, "unknown botKey: " + botKey);
            }
            bots.update(botKey, buildBot(body, webhook, existing));
            registry.reload();
        } catch (IllegalStateException e) {
            return JsonResponses.error(objectMapper, 503, 50301, "bot store unavailable");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("botKey", botKey);
        return JsonResponses.ok(objectMapper, out);
    }

    @DeleteMapping("/console/bots/{botKey}")
    public ResponseEntity<String> deleteBot(@PathVariable("botKey") String botKey) {
        botKey = normalizeBotKey(botKey);
        try {
            if (bots.find(botKey) == null) {
                return JsonResponses.error(objectMapper, 404, 40401, "unknown botKey: " + botKey);
            }
            bots.delete(botKey);
            registry.reload();
        } catch (IllegalStateException e) {
            return JsonResponses.error(objectMapper, 503, 50301, "bot store unavailable");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("botKey", botKey);
        return JsonResponses.ok(objectMapper, out);
    }

    /**
     * 走完整流水线向某 bot 发一条真实测试消息。逻辑与 {@code /admin/test/{botKey}} 一致，
     * 但走会话鉴权——后台页面没法带 X-Api-Token，所以单独开一个会话受保护的入口。
     */
    @PostMapping("/console/bots/{botKey}/test")
    public ResponseEntity<String> testBot(@PathVariable("botKey") String botKey,
                                          HttpServletRequest request) {
        botKey = normalizeBotKey(botKey);
        FeishuProperties.Bot bot = registry.get(botKey);
        if (bot == null) {
            return JsonResponses.error(objectMapper, 404, 40401, "unknown botKey: " + botKey);
        }
        if (!bot.isEnabled()) {
            return JsonResponses.error(objectMapper, 403, 40301, "bot disabled: " + botKey);
        }

        // 该群配置了关键词时带上第一个，好让飞书的关键词校验通过。
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

        // 和其它消息一样留档，这样测试消息也会出现在 /console/logs 里。
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

    /**
     * 从请求体构造一个 Bot。secret 的语义：
     * <ul>
     *   <li>{@code base == null}（新建）：取 body 里的 secret，缺省空串；</li>
     *   <li>{@code base != null}（编辑）：body 无 secret 字段=保留原值；有且为空=清除；有且非空=更新。</li>
     * </ul>
     */
    private FeishuProperties.Bot buildBot(JsonNode body, String webhook, FeishuProperties.Bot base) {
        FeishuProperties.Bot bot = new FeishuProperties.Bot();
        bot.setWebhook(webhook.trim());
        bot.setEnabled(body.has("enabled") ? body.path("enabled").asBoolean(true)
                : (base == null || base.isEnabled()));
        bot.setKeywords(readKeywords(body.path("keywords")));

        if (base == null) {
            bot.setSecret(body.path("secret").asText(""));
        } else if (body.has("secret")) {
            bot.setSecret(body.path("secret").asText(""));
        } else {
            bot.setSecret(base.getSecret());
        }
        return bot;
    }

    private List<String> readKeywords(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String kw = item.asText("").trim();
                if (!kw.isEmpty()) {
                    out.add(kw);
                }
            }
        }
        return out;
    }

    private ArrayNode keywordsNode(List<String> keywords) {
        ArrayNode node = objectMapper.createArrayNode();
        if (keywords != null) {
            for (String keyword : keywords) {
                node.add(keyword);
            }
        }
        return node;
    }

    private static String normalizeBotKey(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static boolean isAuthenticated(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute(AdminSessionInterceptor.AUTH_ATTRIBUTE));
    }
}
