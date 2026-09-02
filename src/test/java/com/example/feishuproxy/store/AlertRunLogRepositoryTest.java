package com.example.feishuproxy.store;

import com.example.feishuproxy.config.FeishuProperties;
import com.example.feishuproxy.model.AlertRunLog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AlertRunLogRepositoryTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static String freshH2() {
        return "jdbc:h2:mem:runlog" + SEQ.incrementAndGet()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    private static AlertRunLogRepository repository(String jdbcUrl) {
        FeishuProperties properties = new FeishuProperties();
        properties.getStore().setJdbcUrl(jdbcUrl);
        properties.getStore().setUsername("sa");
        properties.getStore().setPassword("");
        return new AlertRunLogRepository(properties);
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
        AlertRunLogRepository repository = repository(freshH2());
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
        AlertRunLogRepository repository = repository(freshH2());
        for (int i = 0; i < 5; i++) {
            repository.insert(logEntry("ok", 1, i, null));
        }
        assertEquals(5L, repository.total());
        assertEquals(2, repository.query(2, 0).size());
        assertEquals(2, repository.query(2, 2).size());
        assertEquals(1, repository.query(2, 4).size());
    }

    @Test
    void returnsNullWhenNotConfigured() {
        AlertRunLogRepository repository = new AlertRunLogRepository(new FeishuProperties());
        assertNull(repository.query(10, 0));
        assertEquals(0L, repository.total());
        assertThrows(IllegalStateException.class, () -> repository.insert(logEntry("ok", 1, 0, null)));
    }
}
