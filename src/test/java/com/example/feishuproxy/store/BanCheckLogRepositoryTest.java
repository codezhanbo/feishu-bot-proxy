package com.example.feishuproxy.store;

import com.example.feishuproxy.model.BanCheckLog;
import com.example.feishuproxy.model.BanCheckResult;
import com.example.feishuproxy.store.mapper.BanCheckLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class BanCheckLogRepositoryTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void h2(DynamicPropertyRegistry registry) {
        registry.add("feishu.store.jdbc-url", () ->
                "jdbc:h2:mem:banlog" + SEQ.incrementAndGet()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private BanCheckLogRepository repository;

    @Autowired
    private BanCheckLogMapper mapper;

    @BeforeEach
    void clear() {
        mapper.delete(null);
    }

    private static BanCheckResult success(String banType, int matchCount) throws Exception {
        String json = "{\"success\":true,\"data\":{\"playerName\":\"p1\",\"banStatus\":\"已封禁\","
                + "\"banType\":\"" + banType + "\",\"matchCount\":" + matchCount + ",\"siteUUID\":\"u-1\","
                + "\"survivalLevel\":109,\"survivalTier\":5,\"totalLevel\":2109,\"totalMatches\":8496}}";
        return BanCheckResult.from(MAPPER.readTree(json));
    }

    @Test
    void appendsAndQueriesNewestFirst() throws Exception {
        assertEquals(0L, repository.total());

        repository.record("p1", "steam", success("Cheater", 42));
        repository.record("p2", "kakao", BanCheckResult.failure("boom"));

        assertEquals(2L, repository.total());
        List<BanCheckLog> all = repository.query(50, 0);
        assertEquals(2, all.size());

        // 最新在前：第二条是失败记录
        assertEquals("p2", all.get(0).getPlayer());
        assertFalse(all.get(0).isSuccess());
        assertEquals("boom", all.get(0).getError());
        assertNull(all.get(0).getBanStatus(), "失败记录的结果类字段应为 null");

        // 第一条是成功记录
        assertEquals("p1", all.get(1).getPlayer());
        assertTrue(all.get(1).isSuccess());
        assertEquals("Cheater", all.get(1).getBanType());
        assertEquals(Long.valueOf(42L), all.get(1).getMatchCount());
        assertEquals(Long.valueOf(8496L), all.get(1).getTotalMatches(), "总场次取 totalMatches");
        assertEquals("5段109级", all.get(1).getLevel(), "等级展示为「段+级」");
        assertNotNull(all.get(1).getQueriedDatetime());
    }

    @Test
    void paginatesByOffset() throws Exception {
        for (int i = 0; i < 5; i++) {
            repository.record("p" + i, "steam", success("Cheater", i));
        }
        assertEquals(5L, repository.total());
        assertEquals(2, repository.query(2, 0).size());
        assertEquals(2, repository.query(2, 2).size());
        assertEquals(1, repository.query(2, 4).size());
    }
}
