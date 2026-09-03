package com.example.feishuproxy.store;

import com.example.feishuproxy.core.AlertScheduler;
import com.example.feishuproxy.model.AlertRunLog;
import com.example.feishuproxy.store.mapper.AlertRunLogMapper;
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
class AlertRunLogRepositoryTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @DynamicPropertySource
    static void h2(DynamicPropertyRegistry registry) {
        registry.add("feishu.store.jdbc-url", () ->
                "jdbc:h2:mem:runlog" + SEQ.incrementAndGet()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private AlertRunLogRepository repository;

    @Autowired
    private AlertRunLogMapper mapper;

    // 替换掉真实的调度器，避免它每轮往 alert_run_log 追加「ok/skipped」执行日志污染本测试的计数断言。
    @MockBean
    private AlertScheduler scheduler;

    @BeforeEach
    void clear() {
        mapper.delete(null);
    }

    private static AlertRunLog logEntry(String status, int scanned, int fired, String detail) {
        AlertRunLog entry = new AlertRunLog();
        entry.setExecutedAt(System.currentTimeMillis());
        entry.setStatus(status);
        entry.setRulesScanned(scanned);
        entry.setAlertsFired(fired);
        entry.setDurationMs(12L);
        entry.setDetail(detail);
        return entry;
    }

    @Test
    void appendsAndQueriesNewestFirst() {
        assertEquals(0L, repository.total());

        repository.insert(logEntry("ok", 2, 0, null));
        repository.insert(logEntry("ok", 2, 1, "[{\"ruleId\":1}]"));

        assertEquals(2L, repository.total());
        List<AlertRunLog> all = repository.query(50, 0);
        assertEquals(2, all.size());
        // 最新在前
        assertEquals(1, all.get(0).getAlertsFired());
        assertEquals("[{\"ruleId\":1}]", all.get(0).getDetail());
        assertEquals(0, all.get(1).getAlertsFired());
        assertEquals("ok", all.get(1).getStatus());
    }

    @Test
    void paginatesByOffset() {
        for (int i = 0; i < 5; i++) {
            repository.insert(logEntry("ok", 1, i, null));
        }
        assertEquals(5L, repository.total());
        assertEquals(2, repository.query(2, 0).size());
        assertEquals(2, repository.query(2, 2).size());
        assertEquals(1, repository.query(2, 4).size());
    }
}
