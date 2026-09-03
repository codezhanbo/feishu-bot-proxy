package com.example.feishuproxy.store;

import com.example.feishuproxy.model.Account;
import com.example.feishuproxy.store.mapper.AccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
class AccountRepositoryTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @DynamicPropertySource
    static void h2(DynamicPropertyRegistry registry) {
        registry.add("feishu.store.jdbc-url", () ->
                "jdbc:h2:mem:account" + SEQ.incrementAndGet()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private AccountRepository repository;

    @Autowired
    private AccountMapper mapper;

    @BeforeEach
    void clear() {
        mapper.delete(null);
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
        assertEquals(0, repository.findAll().size());
        assertNull(repository.find("p1"));

        repository.insert(account("p1", "500"));
        Account loaded = repository.find("p1");
        assertNotNull(loaded);
        assertEquals(AccountRepository.NORMAL, loaded.getBanStatus(), "新账号默认正常");
        assertEquals("500", loaded.getLevel());
        assertNull(loaded.getLastCheckedAt(), "从未查询过，最后查询时间为 null");
        assertNull(loaded.getTotalMatches());

        repository.updateProfile("p1", "600", "steam");
        assertEquals("600", repository.find("p1").getLevel());

        repository.delete("p1");
        assertNull(repository.find("p1"));
        assertEquals(0, repository.findAll().size());
    }

    @Test
    void updateFromCheckBackfillsDerivedFields() {
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
}
