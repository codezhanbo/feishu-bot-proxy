package com.example.feishuproxy.store;

import com.example.feishuproxy.config.FeishuProperties;
import com.example.feishuproxy.model.FeishuResponse;
import com.example.feishuproxy.model.GameStats;
import com.example.feishuproxy.model.MessageLog;
import com.example.feishuproxy.model.SendResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageLogRepositoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final byte[] POST = ("{\"msg_type\":\"post\",\"content\":{\"post\":{\"zh_cn\":{"
            + "\"title\":\"微凉Pro游戏数据统计\",\"content\":[[{\"tag\":\"text\",\"text\":\"累计对局:0\"}]]}}}}")
            .getBytes(StandardCharsets.UTF_8);

    private static final byte[] TEXT =
            "{\"msg_type\":\"text\",\"content\":{\"text\":\"[feishu-bot-proxy] test message\"}}"
                    .getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private final List<MessageLogRepository> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        // Windows 不允许 @TempDir 删除仍持有打开句柄的文件。
        for (MessageLogRepository repository : opened) {
            repository.close();
        }
    }

    /** 用临时路径的 hash 作 H2 内存库名：同一路径 == 同一库，不同路径 == 不同库（测试隔离）。 */
    private static String h2Url(Path dbPath) {
        String name = Integer.toHexString(dbPath.toAbsolutePath().toString().hashCode());
        return "jdbc:h2:mem:t" + name + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    private MessageLogRepository open(Path dbPath) {
        return openUrl(h2Url(dbPath), 20480);
    }

    private MessageLogRepository open(Path dbPath, int maxBodyBytes) {
        return openUrl(h2Url(dbPath), maxBodyBytes);
    }

    private MessageLogRepository openUrl(String jdbcUrl, int maxBodyBytes) {
        FeishuProperties properties = new FeishuProperties();
        properties.setMaxBodyBytes(maxBodyBytes);
        properties.getStore().setJdbcUrl(jdbcUrl);
        properties.getStore().setUsername("sa");
        properties.getStore().setPassword("");
        MessageLogRepository repository = new MessageLogRepository(properties, MAPPER);
        opened.add(repository);
        return repository;
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
        MessageLogRepository repository = open(tempDir.resolve("messages.db"));

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
        MessageLogRepository repository = open(tempDir.resolve("messages.db"));

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
        MessageLogRepository repository = open(tempDir.resolve("messages.db"));
        repository.record(Arrays.asList("ops-group"), POST, parsed(POST), "ip",
                0, "success", Collections.singletonList(ok("ops-group")));

        assertEquals(1, repository.query("ops-group", null, 10, 0).size());
        assertEquals(0, repository.query("ops", null, 10, 0).size(),
                "\"ops\" is not a target of a message sent to \"ops-group\"");
    }

    @Test
    void treatsLikeWildcardsInTheBotKeyAsLiteralText() {
        MessageLogRepository repository = open(tempDir.resolve("messages.db"));
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
        MessageLogRepository repository = open(tempDir.resolve("messages.db"));
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
        MessageLogRepository repository = open(tempDir.resolve("messages.db"));
        for (int i = 0; i < 5; i++) {
            repository.record(Arrays.asList("bot-" + i), POST, parsed(POST), "ip", 0, "success",
                    Collections.singletonList(ok("bot-" + i)));
        }

        assertEquals(5L, repository.total());
        assertEquals("bot-4", repository.query(null, null, 2, 0).get(0).getBotKeys());
        assertEquals("bot-2", repository.query(null, null, 2, 2).get(0).getBotKeys());
        assertEquals(1, repository.query(null, null, 2, 4).size());
    }

    @Test
    void recordsSurviveReopening() {
        Path db = tempDir.resolve("messages.db");
        MessageLogRepository first = open(db);
        first.record(Arrays.asList("dev-group"), POST, parsed(POST), "ip", 0, "success",
                Collections.singletonList(ok("dev-group")));
        first.close();

        // 这次改动的全部意义所在：重启绝不能丢失任何数据。
        MessageLogRepository reopened = open(db);
        assertEquals(1L, reopened.total());
        assertEquals("微凉Pro游戏数据统计", reopened.query(null, null, 10, 0).get(0).getTitle());
    }

    @Test
    void rejectedRequestsAreStoredWithNoResultsAndNoPreview() {
        MessageLogRepository repository = open(tempDir.resolve("messages.db"));
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
        MessageLogRepository repository = open(tempDir.resolve("messages.db"), 16);
        byte[] huge = new byte[5000];
        Arrays.fill(huge, (byte) 'x');

        repository.record(Arrays.asList("dev-group"), huge, null, "ip", 41301, "too big",
                Collections.<SendResult>emptyList());

        MessageLog record = repository.query(null, null, 10, 0).get(0);
        assertEquals(16, record.getBody().length(), "the table is never pruned, so cap what lands in it");
        assertEquals(5000, record.getBodyBytes(), "the original size is still worth knowing");
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
        MessageLogRepository repository = open(tempDir.resolve("messages.db"));

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
        MessageLogRepository repository = open(tempDir.resolve("messages.db"));

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
        MessageLogRepository repository = open(tempDir.resolve("messages.db"));

        send(repository, "dev", report("2026-09-01", 1000, 400, 5, "01:00:00"));
        // ops 的累计值完全不同。如果不按 botKey 隔离，dev 的第二条就会拿它当基准。
        send(repository, "ops", report("2026-09-01", 7777, 8888, 9, "09:00:00"));
        send(repository, "dev", report("2026-09-02", 1250, 470, 6, "01:30:20"));

        GameStats stats = repository.query("dev", null, 10, 0).get(0).getStats();
        assertEquals(Long.valueOf(250), stats.getBpGained(), "必须跟 dev 自己的上一条比");
    }

    @Test
    void aPlainMessageBetweenTwoReportsDoesNotBreakTheChain() {
        MessageLogRepository repository = open(tempDir.resolve("messages.db"));

        send(repository, "dev", report("2026-09-01", 1000, 400, 5, "01:00:00"));
        // /admin/test 发的冒烟消息，以及一个畸形请求，都会落在两条战报中间。
        send(repository, "dev", TEXT);
        repository.record(Arrays.asList("dev"), "not json".getBytes(StandardCharsets.UTF_8), null,
                "ip", 40001, "invalid json body", Collections.<SendResult>emptyList());
        send(repository, "dev", report("2026-09-02", 1250, 470, 6, "01:30:20"));

        GameStats stats = repository.query(null, null, 10, 0).get(0).getStats();
        assertEquals(Long.valueOf(250), stats.getBpGained(), "中间那两条不是战报，不该打断增量链");
    }

    @Test
    void addsTheStatsColumnsToADatabaseCreatedBeforeTheyExisted() throws Exception {
        Path db = tempDir.resolve("legacy.db");
        try (Connection legacy = DriverManager.getConnection(h2Url(db), "sa", "");
             Statement statement = legacy.createStatement()) {
            // 加统计列之前的建表语句，逐字保留（Postgres 方言）。
            statement.execute("CREATE TABLE message_log (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                    + "created_at BIGINT NOT NULL,bot_keys TEXT NOT NULL,msg_type TEXT,title TEXT,"
                    + "text_preview TEXT,body TEXT NOT NULL,body_bytes INTEGER NOT NULL,client_ip TEXT,"
                    + "success INTEGER NOT NULL,code INTEGER NOT NULL,msg TEXT,results TEXT)");
            statement.execute("INSERT INTO message_log (created_at,bot_keys,msg_type,body,body_bytes,"
                    + "success,code,results) VALUES (1,'dev','text','{}',2,1,0,'[]')");
        }

        MessageLogRepository repository = open(db);

        List<MessageLog> old = repository.query(null, null, 10, 0);
        assertEquals(1, old.size(), "旧行必须还读得回来");
        assertNull(old.get(0).getStats(), "加列之前写入的行没有统计数据");

        send(repository, "dev", report("2026-09-01", 1000, 400, 5, "01:00:00"));
        assertEquals("2026-09-01", repository.query(null, null, 10, 0).get(0).getStats().getStatDate(),
                "补上的列要能正常写入");
    }

    @Test
    void addsTheCreateDatetimeColumnAndLeavesLegacyRowsNull() throws Exception {
        Path db = tempDir.resolve("legacy-no-datetime.db");
        try (Connection legacy = DriverManager.getConnection(h2Url(db), "sa", "");
             Statement statement = legacy.createStatement()) {
            // create_datetime 加列之前的建表语句，逐字保留（Postgres 方言）。
            statement.execute("CREATE TABLE message_log (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                    + "created_at BIGINT NOT NULL,bot_keys TEXT NOT NULL,msg_type TEXT,title TEXT,"
                    + "text_preview TEXT,body TEXT NOT NULL,body_bytes INTEGER NOT NULL,client_ip TEXT,"
                    + "success INTEGER NOT NULL,code INTEGER NOT NULL,msg TEXT,results TEXT)");
            statement.execute("INSERT INTO message_log (created_at,bot_keys,msg_type,body,body_bytes,"
                    + "success,code,results) VALUES (1,'dev','text','{}',2,1,0,'[]')");
        }

        MessageLogRepository repository = open(db);

        assertNull(repository.query(null, null, 10, 0).get(0).getCreateDatetime(),
                "加列之前写入的行，create_datetime 保持 NULL，不回填");

        send(repository, "dev", report("2026-09-01", 1000, 400, 5, "01:00:00"));
        MessageLog fresh = repository.query(null, null, 10, 0).get(0);
        assertNotNull(fresh.getCreateDatetime(), "新写入的行要带上可读时间");
        assertEquals(fresh.getTime(), fresh.getCreateDatetime());
    }

    @Test
    void degradesInsteadOfThrowingWhenTheDatabaseCannotBeReached() {
        // 指向一个连不上的地址。这里绝不能让任何异常逃逸出去。
        MessageLogRepository repository = openUrl("jdbc:postgresql://127.0.0.1:1/nope?connectTimeout=1", 20480);

        assertFalse(repository.isEnabled());
        repository.record(Arrays.asList("dev-group"), POST, parsed(POST), "ip", 0, "success",
                Collections.singletonList(ok("dev-group")));
        assertEquals(0, repository.query(null, null, 10, 0).size());
        assertEquals(0L, repository.total());
    }

    @Test
    void canBeTurnedOffEntirely() {
        FeishuProperties properties = new FeishuProperties();
        properties.getStore().setEnabled(false);

        MessageLogRepository repository = new MessageLogRepository(properties, MAPPER);
        opened.add(repository);

        assertFalse(repository.isEnabled());
        repository.record(Arrays.asList("dev-group"), POST, parsed(POST), "ip", 0, "success",
                Collections.singletonList(ok("dev-group")));
        assertEquals(0L, repository.total());
    }
}
