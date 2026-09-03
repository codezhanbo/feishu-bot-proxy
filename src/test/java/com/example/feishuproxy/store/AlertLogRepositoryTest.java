package com.example.feishuproxy.store;

import com.example.feishuproxy.core.AlertScheduler;
import com.example.feishuproxy.model.AlertLog;
import com.example.feishuproxy.store.mapper.AlertLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class AlertLogRepositoryTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @DynamicPropertySource
    static void h2(DynamicPropertyRegistry registry) {
        registry.add("feishu.store.jdbc-url", () ->
                "jdbc:h2:mem:alertlog" + SEQ.incrementAndGet()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private AlertLogRepository repository;

    @Autowired
    private AlertLogMapper mapper;

    // 替换掉真实的调度器，避免它每轮往 alert_log 追加记录污染本测试的计数断言。
    @MockBean
    private AlertScheduler scheduler;

    @BeforeEach
    void clear() {
        mapper.delete(null);
    }

    private static AlertLog entry(long triggeredAt, String botKey) {
        AlertLog e = new AlertLog();
        e.setRuleId(1L);
        e.setBotKey(botKey);
        e.setAlertBotKey("ops-group");
        e.setThresholdMinutes(30);
        e.setIdleMinutes(40);
        e.setMessage("告警：" + botKey);
        e.setSendCode(0);
        e.setSendMsg("ok");
        e.setTriggeredAt(triggeredAt);
        return e;
    }

    @Test
    void appendsAndQueriesNewestFirst() {
        assertEquals(0L, repository.total());

        repository.insert(entry(1000L, "dev-group"));
        repository.insert(entry(2000L, "ops-group"));

        assertEquals(2L, repository.total());
        List<AlertLog> all = repository.query(50, 0);
        assertEquals(2, all.size());
        // 最新在前
        assertEquals("ops-group", all.get(0).getBotKey());
        assertEquals("dev-group", all.get(1).getBotKey());
    }

    @Test
    void paginatesByOffset() {
        for (int i = 0; i < 5; i++) {
            repository.insert(entry(1000L + i, "bot-" + i));
        }
        assertEquals(5L, repository.total());
        assertEquals(2, repository.query(2, 0).size());
        assertEquals(2, repository.query(2, 2).size());
        assertEquals(1, repository.query(2, 4).size());
    }

    @Test
    void filtersByBotKeyAndTimeRange() {
        repository.insert(entry(1000L, "dev-group"));
        repository.insert(entry(2000L, "ops-group"));

        assertEquals(1, repository.query("dev", null, null, 50, 0).size(),
                "botKey 模糊匹配应命中 dev-group");
        assertEquals(0, repository.query("zzz", null, null, 50, 0).size());

        assertEquals(1, repository.count(null, 1500L, null),
                "[from, to) 半开区间：from=1500 只命中 ops-group(2000)");
        assertEquals(1, repository.query(null, null, 1500L, 50, 0).size(),
                "to=1500 只命中 dev-group(1000)");
    }
}
