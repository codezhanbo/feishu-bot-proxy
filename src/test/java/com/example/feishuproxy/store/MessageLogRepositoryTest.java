package com.example.feishuproxy.store;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.feishuproxy.model.FeishuResponse;
import com.example.feishuproxy.model.GameStats;
import com.example.feishuproxy.model.MessageLog;
import com.example.feishuproxy.model.SendResult;
import com.example.feishuproxy.store.entity.MessageLogEntity;
import com.example.feishuproxy.store.mapper.MessageLogMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 仓储已迁到 MyBatis-Plus，这里在真实 H2 + Spring 上下文里断言写入/回读/差分语义不变。 */
@SpringBootTest
class MessageLogRepositoryTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final byte[] POST = ("{\"msg_type\":\"post\",\"content\":{\"post\":{\"zh_cn\":{"
            + "\"title\":\"微凉Pro游戏数据统计\",\"content\":[[{\"tag\":\"text\",\"text\":\"累计对局:0\"}]]}}}}")
            .getBytes(StandardCharsets.UTF_8);

    private static final byte[] TEXT =
            "{\"msg_type\":\"text\",\"content\":{\"text\":\"[feishu-bot-proxy] test message\"}}"
                    .getBytes(StandardCharsets.UTF_8);

    @DynamicPropertySource
    static void h2(DynamicPropertyRegistry registry) {
        registry.add("feishu.store.jdbc-url", () ->
                "jdbc:h2:mem:messages" + SEQ.incrementAndGet()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private MessageLogRepository repository;

    @Autowired
    private MessageLogMapper mapper;

    @BeforeEach
    void clear() {
        mapper.delete(null);
    }

    private static JsonNode parsed(byte[] body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SendResult ok(String botKey) {
        String body = "{\"code\":0,\"msg\":\"success\"}";
        return SendResult.fromFeishu(botKey, 200, body, FeishuResponse.parse(MAPPER, body), 1, 12L);
    }

    private static SendResult failed(String botKey) {
        return SendResult.localError(botKey, 404, 40401, "unknown botKey: " + botKey);
    }

    @Test
    void storesEveryColumnOfAMessage() {
        repository.record(Arrays.asList("dev-group"), POST, parsed(POST), "10.0.0.7",
                0, "success", Collections.singletonList(ok("dev-group")));

        List<MessageLog> records = repository.query(null, null, 10, 0);
        assertEquals(1, records.size());

        MessageLog record = records.get(0);
        assertEquals("dev-group", record.getBotKeys());
        assertEquals("post", record.getMsgType());
        assertEquals("微凉Pro游戏数据统计", record.getTitle());
        assertEquals("累计对局:0", record.getTextPreview());
        assertEquals(new String(POST, StandardCharsets.UTF_8), record.getBody());
        assertEquals(POST.length, record.getBodyBytes());
        assertEquals("10.0.0.7", record.getClientIp());
        assertTrue(record.isSuccess());
        assertEquals(0, record.getCode());
        assertNotNull(record.getTime());
        assertEquals(record.getTime(), record.getCreateDatetime(),
                "create_datetime 列与 getTime() 同值，只是持久化了一份");
        assertEquals(1, record.getResults().size());
        assertEquals("dev-group", record.getResults().get(0).path("botKey").asText());
        assertEquals(12L, record.getResults().get(0).path("costMs").asLong());
    }

    @Test
    void legacyMultiTargetRowsStillReadBack() {
        // 广播已移除，但广播存在期间写入的行会指向多个目标。列结构和 botKey 过滤器
        // 仍需兼容这些行。
        repository.record(Arrays.asList("dev-group", "ops-group"), POST, parsed(POST), "10.0.0.7",
                1, "partial_failure", Arrays.asList(ok("dev-group"), failed("ops-group")));

        List<MessageLog> records = repository.query(null, null, 10, 0);
        assertEquals(1, records.size(), "one request is one row, whatever the fan-out was");
        assertEquals("dev-group,ops-group", records.get(0).getBotKeys());
        assertEquals(2, records.get(0).getResults().size());
        assertFalse(records.get(0).isSuccess(), "one failed target makes the whole request a failure");
        assertEquals(1, repository.query("ops-group", null, 10, 0).size(), "filter must still find it");
    }

    @Test
    void filtersByBotKeyWithoutMatchingOnAPrefix() {
        repository.record(Arrays.asList("ops-group"), POST, parsed(POST), "ip",
                0, "success", Collections.singletonList(ok("ops-group")));

        assertEquals(1, repository.query("ops-group", null, 10, 0).size());
        assertEquals(0, repository.query("ops", null, 10, 0).size(),
                "\"ops\" is not a target of a message sent to \"ops-group\"");
    }

    @Test
    void treatsLikeWildcardsInTheBotKeyAsLiteralText() {
        repository.record(Arrays.asList("ops-group"), POST, parsed(POST), "ip",
                0, "success", Collections.singletonList(ok("ops-group")));
        repository.record(Arrays.asList("a_b"), POST, parsed(POST), "ip",
                0, "success", Collections.singletonList(ok("a_b")));

        assertEquals(0, repository.query("%", null, 10, 0).size(),
                "% 是 LIKE 通配符，不转义的话这一句会捞出整张表");
        assertEquals(0, repository.query("a_", null, 10, 0).size(), "_ 同理，匹配任意单字符");
        assertEquals(1, repository.query("a_b", null, 10, 0).size(),
                "转义之后，含下划线的 key 仍然要能按字面量查到");
    }

    @Test
    void filtersByOutcome() {
        repository.record(Arrays.asList("a"), POST, parsed(POST), "ip", 0, "success",
                Collections.singletonList(ok("a")));
        repository.record(Arrays.asList("b"), POST, parsed(POST), "ip", 40401, "unknown",
                Collections.singletonList(failed("b")));

        assertEquals(1, repository.query(null, true, 10, 0).size());
        assertEquals(1, repository.query(null, false, 10, 0).size());
        assertEquals(2, repository.query(null, null, 10, 0).size());
    }

    @Test
    void pagesNewestFirst() {
        for (int i = 0; i < 5; i++) {
            repository.record(Arrays.asList("bot-" + i), POST, parsed(POST), "ip", 0, "success",
                    Collections.singletonList(ok("bot-" + i)));
        }

        assertEquals(5, repository.query(null, null, 20, 0).size());
        assertEquals("bot-4", repository.query(null, null, 2, 0).get(0).getBotKeys());
        assertEquals("bot-2", repository.query(null, null, 2, 2).get(0).getBotKeys());
        assertEquals(1, repository.query(null, null, 2, 4).size());
    }

    @Test
    void rejectedRequestsAreStoredWithNoResultsAndNoPreview() {
        byte[] garbage = "not json".getBytes(StandardCharsets.UTF_8);

        repository.record(Arrays.asList("dev-group"), garbage, null, "ip",
                40001, "invalid json body", Collections.<SendResult>emptyList());

        MessageLog record = repository.query(null, null, 10, 0).get(0);
        assertEquals("not json", record.getBody());
        assertNull(record.getMsgType());
        assertNull(record.getTitle());
        assertFalse(record.isSuccess(), "nothing was delivered, so this is not a success");
        assertEquals(0, record.getResults().size());
    }

    @Test
    void oversizedBodiesAreTruncatedButTheirRealLengthIsKept() {
        // 测试配置里 max-body-bytes=20480（见 application-test）。造一个超长的请求体，
        // 验证落库只截到 20480 字节，但原始长度仍保留。
        byte[] huge = new byte[30000];
        Arrays.fill(huge, (byte) 'x');

        repository.record(Arrays.asList("dev-group"), huge, null, "ip", 41301, "too big",
                Collections.<SendResult>emptyList());

        MessageLog record = repository.query(null, null, 10, 0).get(0);
        assertEquals(20480, record.getBody().length(),
                "the table is never pruned, so cap what lands in it");
        assertEquals(30000, record.getBodyBytes(), "the original size is still worth knowing");
    }

    @Test
    void repairRewritesStaleCreateDatetime() {
        repository.record(Arrays.asList("dev-group"), POST, parsed(POST), "ip",
                0, "success", Collections.singletonList(ok("dev-group")));

        // 抓出唯一一行，拿到权威的 created_at，再把 create_datetime 故意改坏，模拟旧代码烧错的 UTC 值。
        MessageLogEntity entity = mapper.selectList(null).get(0);
        Long id = entity.getId();
        String correct = MessageLog.formatDateTime(entity.getCreatedAt());
        mapper.update(null, new LambdaUpdateWrapper<MessageLogEntity>()
                .eq(MessageLogEntity::getId, id)
                .set(MessageLogEntity::getCreateDatetime, "1970-01-01 00:00:00.000"));

        assertEquals(1, repository.repairCreateDatetime(200), "应修复被改坏的那一行");
        assertEquals(correct, mapper.selectById(id).getCreateDatetime(),
                "修复后与权威 created_at 重算出的北京时间一致");

        assertEquals(0, repository.repairCreateDatetime(200), "第二次跑应是 no-op");
    }

    /** 一条战报，数值可控。参数各不相同，字段读串了才看得出来。 */
    private static byte[] report(String date, long bp, long exp, int level, String duration) {
        return ("{\"msg_type\":\"post\",\"content\":{\"post\":{\"zh_cn\":{"
                + "\"title\":\"微凉Pro游戏数据统计 " + date + "\",\"content\":[["
                + "{\"tag\":\"text\",\"text\":\"💰累计BP:" + bp + " | 📌生存经验:" + exp + "\\n\"},"
                + "{\"tag\":\"text\",\"text\":\"⭐生存等级:" + level + " | ⏱️耗时:" + duration + "\"}]]}}}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void send(MessageLogRepository repository, String botKey, byte[] body) {
        repository.record(Arrays.asList(botKey), body, parsed(body), "ip", 0, "success",
                Collections.singletonList(ok(botKey)));
    }

    @Test
    void theFirstReportHasNoDeltasButKeepsItsOwnValues() {
        send(repository, "dev", report("2026-09-01", 1000, 400, 5, "01:00:00"));

        GameStats stats = repository.query(null, null, 10, 0).get(0).getStats();
        assertNotNull(stats);
        assertEquals("2026-09-01", stats.getStatDate());
        assertEquals(Integer.valueOf(5), stats.getSurvivalLevel(), "等级是原值，不做差分");
        assertNull(stats.getBpGained(), "没有上一条可比对，增量只能是 null");
        assertNull(stats.getExpGained());
        assertNull(stats.getDuration());
    }

    @Test
    void theSecondReportIsDiffedAgainstTheFirst() {
        send(repository, "dev", report("2026-09-01", 1000, 400, 5, "01:00:00"));
        send(repository, "dev", report("2026-09-02", 1250, 470, 6, "01:30:20"));

        GameStats stats = repository.query(null, null, 10, 0).get(0).getStats();
        assertEquals("2026-09-02", stats.getStatDate());
        assertEquals(Long.valueOf(250), stats.getBpGained(), "1250 - 1000");
        assertEquals(Long.valueOf(70), stats.getExpGained(), "470 - 400");
        assertEquals("00:30:20", stats.getDuration());
    }

    @Test
    void deltasAreScopedToTheSameBotKey() {
        send(repository, "dev", report("2026-09-01", 1000, 400, 5, "01:00:00"));
        // ops 的累计值完全不同。如果不按 botKey 隔离，dev 的第二条就会拿它当基准。
        send(repository, "ops", report("2026-09-01", 7777, 8888, 9, "09:00:00"));
        send(repository, "dev", report("2026-09-02", 1250, 470, 6, "01:30:20"));

        GameStats stats = repository.query("dev", null, 10, 0).get(0).getStats();
        assertEquals(Long.valueOf(250), stats.getBpGained(), "必须跟 dev 自己的上一条比");
    }

    @Test
    void aPlainMessageBetweenTwoReportsDoesNotBreakTheChain() {
        send(repository, "dev", report("2026-09-01", 1000, 400, 5, "01:00:00"));
        // /admin/test 发的冒烟消息，以及一个畸形请求，都会落在两条战报中间。
        send(repository, "dev", TEXT);
        repository.record(Arrays.asList("dev"), "not json".getBytes(StandardCharsets.UTF_8), null,
                "ip", 40001, "invalid json body", Collections.<SendResult>emptyList());
        send(repository, "dev", report("2026-09-02", 1250, 470, 6, "01:30:20"));

        GameStats stats = repository.query(null, null, 10, 0).get(0).getStats();
        assertEquals(Long.valueOf(250), stats.getBpGained(), "中间那两条不是战报，不该打断增量链");
    }
}
