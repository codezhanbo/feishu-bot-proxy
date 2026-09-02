package com.example.feishuproxy.store;

import com.example.feishuproxy.config.FeishuProperties;
import com.example.feishuproxy.model.Account;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountRepositoryTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static String freshH2() {
        return "jdbc:h2:mem:account" + SEQ.incrementAndGet()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    private static AccountRepository repository(String jdbcUrl) {
        FeishuProperties properties = new FeishuProperties();
        properties.getStore().setJdbcUrl(jdbcUrl);
        properties.getStore().setUsername("sa");
        properties.getStore().setPassword("");
        return new AccountRepository(properties);
    }

    private static Account account(String accountId, String level) {
        Account account = new Account();
        account.setAccountId(accountId);
        account.setBanStatus(AccountRepository.NORMAL);
        account.setLevel(level);
        return account;
    }

    @Test
    void crudRoundTrips() {
        AccountRepository repository = repository(freshH2());
        assertEquals(0, repository.findAll().size());
        assertNull(repository.find("p1"));

        repository.insert(account("p1", "500"));
        Account loaded = repository.find("p1");
        assertNotNull(loaded);
        assertEquals(AccountRepository.NORMAL, loaded.getBanStatus(), "新账号默认正常");
        assertEquals("500", loaded.getLevel());
        assertNull(loaded.getLastCheckedAt(), "从未查询过，最后查询时间为 null");
        assertNull(loaded.getTotalMatches());

        repository.updateLevel("p1", "600");
        assertEquals("600", repository.find("p1").getLevel());

        repository.delete("p1");
        assertNull(repository.find("p1"));
        assertEquals(0, repository.findAll().size());
    }

    @Test
    void updateFromCheckBackfillsDerivedFields() {
        AccountRepository repository = repository(freshH2());
        repository.insert(account("p1", "500"));

        repository.updateFromCheck("p1", AccountRepository.BANNED, "5段109级", 8496L, "2026-09-02 10:00:00");
        Account updated = repository.find("p1");
        assertEquals(AccountRepository.BANNED, updated.getBanStatus());
        assertEquals("5段109级", updated.getLevel(), "等级随查询自动更新（段+级展示）");
        assertEquals(Long.valueOf(8496L), updated.getTotalMatches(), "总场次取 totalMatches");
        assertEquals("2026-09-02 10:00:00", updated.getLastCheckedAt());

        // 未命中的账号是 no-op，不会凭空建出账号。
        repository.updateFromCheck("nobody", AccountRepository.NORMAL, null, 1L, "x");
        assertNull(repository.find("nobody"));
    }

    @Test
    void returnsNullWhenNotConfigured() {
        AccountRepository repository = new AccountRepository(new FeishuProperties());
        assertNull(repository.findAll());
        assertThrows(IllegalStateException.class, () -> repository.find("p1"));
        assertThrows(IllegalStateException.class, () -> repository.insert(account("p1", null)));
        assertThrows(IllegalStateException.class, () -> repository.updateLevel("p1", "600"));
        assertThrows(IllegalStateException.class, () -> repository.delete("p1"));
        // 查询旁路的自动更新不抛异常。
        assertDoesNotThrow(() -> repository.updateFromCheck("p1", AccountRepository.NORMAL, null, null, null));
    }
}