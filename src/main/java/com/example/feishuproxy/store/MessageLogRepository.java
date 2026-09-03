package com.example.feishuproxy.store;

import com.example.feishuproxy.config.FeishuProperties;
import com.example.feishuproxy.core.GameStatsParser;
import com.example.feishuproxy.core.MessagePreview;
import com.example.feishuproxy.model.GameStats;
import com.example.feishuproxy.model.MessageLog;
import com.example.feishuproxy.model.SendResult;
import com.example.feishuproxy.store.entity.MessageLogEntity;
import com.example.feishuproxy.store.mapper.MessageLogMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 每条入站消息都追加写进一张 Postgres 表（Supabase），永不清理。
 * <p>
 * 一个请求一行。一个请求只面向一个群组，但 {@code bot_keys} 和 {@code results} 两列保持
 * 复数形态：广播功能移除之前写入的行包含多个目标，而这些行仍由同一套代码查询。
 * <p>
 * 迁移到 MyBatis-Plus 后，连接由 HikariCP 池统一管理，不再手工维护长连接与断线重连；
 * 建表/补列已迁到 {@code db/schema.sql}，启动前手工执行。
 * <p>
 * <strong>持久化绝不能破坏中转。</strong>任何意外——连不上库、连接中断——都只是让本组件降级，
 * 转发不受影响。因此 {@link #record} 不抛异常。
 */
@Component
public class MessageLogRepository {

    private static final Logger log = LoggerFactory.getLogger(MessageLogRepository.class);

    /**
     * 往回找「上一条战报」时最多翻这么多条 post。
     * 用有限回看而不是一路扫到底，是为了不让每次写入退化成全表扫描；
     * 代价是同一个群里连续来 10 条以上非战报的 post 时，增量链会断在那一行。
     */
    private static final int LOOKBACK = 10;

    /** 单页 /admin/logs 的上限，避免过大的 limit 把整张表拉进内存。 */
    public static final int MAX_PAGE = 200;

    private final MessageLogMapper mapper;
    private final ObjectMapper objectMapper;

    /** 请求体的截断上限与中转服务拒转发的阈值一致。 */
    private final int maxStoredBodyBytes;

    /** 廉价的 COUNT(*)：这张表只增不减，全表扫描会一直占着写锁。 */
    private final AtomicLong total = new AtomicLong();

    /** 当前是否可用（供 /admin/logs 判断 503）。启动时播种，后续操作失败置 false、成功置 true。 */
    private volatile boolean enabled;

    public MessageLogRepository(MessageLogMapper mapper, ObjectMapper objectMapper, FeishuProperties properties) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.maxStoredBodyBytes = Math.max(1, properties.getMaxBodyBytes());
        try {
            Long count = mapper.selectCount(null);
            this.total.set(count == null ? 0L : count);
            this.enabled = true;
            log.info("message store ready ({} rows)", total.get());
        } catch (Exception e) {
            log.warn("message store unavailable, messages will not be persisted", e);
            this.enabled = false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long total() {
        return total.get();
    }

    /**
     * 某 botKey 最近一条记录的落库时间（epoch 毫秒）。没有该 botKey 的任何记录、或数据库
     * 不可用时返回 {@code null}。告警调度器据此判断「这个 bot 已经多久没消息了」。
     */
    public Long lastCreatedAt(String botKey) {
        try {
            Long result = mapper.selectLastCreatedAt("%," + escapeLike(botKey) + ",%");
            enabled = true;
            return result;
        } catch (Exception e) {
            enabled = false;
            log.warn("failed to query last message time (botKey={})", botKey, e);
            return null;
        }
    }

    /**
     * 持久化一个请求。绝不抛异常——这里的失败绝不能改变已经答复给调用方的结果。
     *
     * @param botKeys 解析出的目标机器人列表，请求尚未走到那一步时为空
     * @param body    调用方的原始字节，加签之前的内容
     * @param parsed  同一份请求体解析后的结果，无法解析时为 null
     * @param code    就是答复给调用方的那个 code
     * @param results 每个尝试过的机器人对应一条；在分派之前就被拒掉的请求则为空
     */
    public void record(List<String> botKeys, byte[] body, JsonNode parsed, String clientIp,
                       int code, String msg, List<SendResult> results) {
        if (body == null || body.length == 0) {
            return;
        }
        try {
            insert(botKeys, body, parsed, clientIp, code, msg, results);
        } catch (Exception e) {
            enabled = false;
            log.warn("failed to persist message (botKeys={} code={})", botKeys, code, e);
        }
    }

    private MessageLogEntity buildEntity(List<String> botKeys, byte[] body, JsonNode parsed, String clientIp,
                                         int code, String msg, List<SendResult> results, GameStats stats) {
        // 超长请求体会被 413 拒掉，但仍然要留档，所以得限制真正落盘的量：这张表永不清理，
        // 不能让一次恶意 POST 就把磁盘写满。
        byte[] stored = body.length > maxStoredBodyBytes ? Arrays.copyOf(body, maxStoredBodyBytes) : body;

        boolean success = !results.isEmpty();
        ArrayNode items = objectMapper.createArrayNode();
        for (SendResult result : results) {
            success &= result.isSuccess();
            ObjectNode item = items.addObject();
            item.put("botKey", result.getBotKey());
            item.put("success", result.isSuccess());
            item.put("code", result.getCode());
            item.put("msg", result.getMsg());
            item.put("attempts", result.getAttempts());
            item.put("costMs", result.getCostMs());
        }

        long now = System.currentTimeMillis();
        MessageLogEntity entity = new MessageLogEntity();
        entity.setCreatedAt(now);
        entity.setCreateDatetime(MessageLog.formatDateTime(now));
        entity.setBotKeys(String.join(",", botKeys));
        entity.setMsgType(parsed == null ? null : parsed.path("msg_type").asText(null));
        entity.setTitle(MessagePreview.title(parsed));
        entity.setTextPreview(MessagePreview.preview(parsed));
        entity.setBody(new String(stored, StandardCharsets.UTF_8));
        entity.setBodyBytes(body.length);
        entity.setClientIp(clientIp);
        entity.setSuccess(success ? 1 : 0);
        entity.setCode(code);
        entity.setMsg(msg);
        entity.setResults(items.toString());
        entity.setStatDate(stats == null ? null : stats.getStatDate());
        entity.setSurvivalLevel(stats == null ? null : stats.getSurvivalLevel());
        entity.setExpGained(stats == null ? null : stats.getExpGained());
        entity.setBpGained(stats == null ? null : stats.getBpGained());
        entity.setDuration(stats == null ? null : stats.getDuration());
        return entity;
    }

    /**
     * 查上一条战报、算差分、写入——三件事必须在同一把锁内完成，否则两个并发请求会读到同一条
     * 「上一条」，各自算出重复的增量。
     */
    private synchronized void insert(List<String> botKeys, byte[] body, JsonNode parsed, String clientIp,
                                     int code, String msg, List<SendResult> results) {
        GameStatsParser.Snapshot snapshot = GameStatsParser.parse(parsed);
        GameStats stats = snapshot == null
                ? null : GameStatsParser.delta(snapshot, previousSnapshot(botKeys));

        MessageLogEntity entity = buildEntity(botKeys, body, parsed, clientIp, code, msg, results, stats);
        mapper.insert(entity);
        total.incrementAndGet();
        enabled = true;
    }

    /**
     * 同 botKey 的上一条战报。往回翻最多 {@link #LOOKBACK} 条 post，取第一条解析得出快照的——
     * 中间可能夹着 {@code /admin/test} 发的 text 消息或畸形请求。
     * <p>
     * 发送失败的战报同样算数：游戏侧的累计值已经涨上去了，跳过它会让下一条的增量翻倍。
     */
    private GameStatsParser.Snapshot previousSnapshot(List<String> botKeys) {
        if (botKeys.size() != 1) {
            // 没有目标（发送前就被拒），或者是遗留的多目标行——都无从判断该跟谁比。
            return null;
        }
        try {
            List<String> bodies = mapper.selectRecentPostBodies(
                    "%," + escapeLike(botKeys.get(0)) + ",%", LOOKBACK);
            for (String raw : bodies) {
                GameStatsParser.Snapshot snapshot = GameStatsParser.parse(readJson(raw));
                if (snapshot != null) {
                    return snapshot;
                }
            }
        } catch (Exception e) {
            log.warn("failed to look up the previous stats report (botKeys={})", botKeys, e);
        }
        return null;
    }

    private JsonNode readJson(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            // 存进去的可能本来就不是 JSON（被拒的畸形请求也会留档），不是异常情况。
            return null;
        }
    }

    public List<MessageLog> query(String botKey, Boolean success, int limit, int offset) {
        return query(botKey, success, null, null, null, limit, offset);
    }

    /**
     * 最新在前。故意按主键而非 {@code created_at} 排序：这张表只追加写入，
     * 主键本身就是时间序，无需额外索引。
     *
     * @param botKey      按单个目标过滤，null 表示不限
     * @param success     按结果过滤，null 表示不限
     * @param keyword     按 title / text_preview / body 模糊匹配，null 或空白表示不限
     * @param fromEpochMs 落库时间下限（含，epoch 毫秒），null 表示不限
     * @param toEpochMs   落库时间上限（含，epoch 毫秒），null 表示不限
     */
    public List<MessageLog> query(String botKey, Boolean success, String keyword,
                                  Long fromEpochMs, Long toEpochMs, int limit, int offset) {
        List<MessageLog> out = new ArrayList<>();
        try {
            // 对于「一个请求可指向多个群组」时代遗留的行，bot_keys 用逗号拼接，
            // 所以两侧都包上逗号，避免把 "ops-group" 里的 "ops" 也匹配进去。
            String botKeyPattern = botKey == null ? null : "%," + escapeLike(botKey) + ",%";
            String keywordPattern = (keyword == null || keyword.trim().isEmpty())
                    ? null : "%" + escapeLike(keyword.trim()) + "%";
            List<MessageLogEntity> rows = mapper.selectPage(
                    botKeyPattern,
                    success == null ? null : (success ? 1 : 0),
                    keywordPattern,
                    fromEpochMs, toEpochMs,
                    Math.max(1, Math.min(limit, MAX_PAGE)),
                    Math.max(0, offset));
            for (MessageLogEntity row : rows) {
                out.add(toView(row));
            }
            enabled = true;
        } catch (Exception e) {
            enabled = false;
            log.warn("failed to query message log", e);
        }
        return out;
    }

    /**
     * botKey 是查询参数，直接拼进 LIKE 模式的话 {@code %} 和 {@code _} 会被当成通配符——
     * {@code ?botKey=%} 就能捞出整张表。用 {@code !} 作转义符（Postgres 与 H2 都无歧义），
     * 必须先转义 {@code !} 本身，否则会把后面补上的转义符再转义一次。
     */
    private static String escapeLike(String raw) {
        return raw.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private MessageLog toView(MessageLogEntity e) {
        JsonNode parsedResults = null;
        if (e.getResults() != null) {
            try {
                // 必须重新解析：把原始字符串直接交给 Jackson 会被转义成 "[{...}]"。
                parsedResults = objectMapper.readTree(e.getResults());
            } catch (Exception ex) {
                log.warn("unreadable results column on message_log id={}", e.getId(), ex);
            }
        }
        GameStats stats = toStats(e);
        return new MessageLog(e.getId() == null ? 0L : e.getId(), e.getCreatedAt(), e.getCreateDatetime(),
                e.getBotKeys(), e.getMsgType(), e.getTitle(), e.getTextPreview(), e.getBody(),
                e.getBodyBytes(), e.getClientIp(), e.getSuccess() != null && e.getSuccess() != 0,
                e.getCode(), e.getMsg(), parsedResults, stats);
    }

    /** 五列全空说明这行不是战报（也可能是加列之前写入的旧行），返回 null 而不是一个空壳对象。 */
    private static GameStats toStats(MessageLogEntity e) {
        GameStats stats = new GameStats(e.getStatDate(), e.getSurvivalLevel(), e.getExpGained(),
                e.getBpGained(), e.getDuration());
        return stats.isEmpty() ? null : stats;
    }
}
