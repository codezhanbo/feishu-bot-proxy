package com.example.feishuproxy.core;

import com.example.feishuproxy.model.Account;
import com.example.feishuproxy.model.BanCheckResult;
import com.example.feishuproxy.store.AccountRepository;
import com.example.feishuproxy.store.BanCheckLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BanCheckSchedulerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PubgBanClient client = mock(PubgBanClient.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final BanCheckLogRepository banCheckLog = mock(BanCheckLogRepository.class);
    private final BanCheckScheduler scheduler = new BanCheckScheduler(client, accounts, banCheckLog);

    private static Account account(String id, String platform) {
        Account a = new Account();
        a.setAccountId(id);
        a.setPlatform(platform);
        return a;
    }

    /** 构造一个成功查询结果：5段109级、总场次 8496。 */
    private static BanCheckResult okResult(String banStatus, String banType) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("playerName", "p1");
        data.put("platform", "steam");
        if (banStatus != null) {
            data.put("banStatus", banStatus);
        }
        data.put("banType", banType);
        data.put("survivalLevel", 109);
        data.put("survivalTier", 5);
        data.put("totalMatches", 8496);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("success", true);
        body.set("data", data);
        return BanCheckResult.from(body);
    }

    @Test
    void scansEachAccountAndUpdates() {
        when(accounts.findAll()).thenReturn(Arrays.asList(account("a", "steam"), account("b", "kakao")));
        when(client.check(any(), any())).thenReturn(okResult("正常", "Innocent"));

        scheduler.run();

        verify(client).check("a", "steam");
        verify(client).check("b", "kakao");
        verify(banCheckLog, times(2)).record(any(), any(), any());
        verify(accounts, times(2)).updateFromCheck(any(), any(), any(), any(), any());
    }

    @Test
    void doesNothingWhenNoAccounts() {
        when(accounts.findAll()).thenReturn(Collections.emptyList());

        scheduler.run();

        verify(client, never()).check(any(), any());
        verify(banCheckLog, never()).record(any(), any(), any());
    }

    @Test
    void skipsWhenStoreUnavailable() {
        when(accounts.findAll()).thenReturn(null);

        scheduler.run();

        verify(client, never()).check(any(), any());
        verify(banCheckLog, never()).record(any(), any(), any());
    }

    @Test
    void failedCheckIsRecordedButNotUpdated() {
        when(accounts.findAll()).thenReturn(Collections.singletonList(account("a", "steam")));
        when(client.check(any(), any())).thenReturn(BanCheckResult.failure("查无此人"));

        scheduler.run();

        verify(banCheckLog).record(eq("a"), eq("steam"), any());
        verify(accounts, never()).updateFromCheck(any(), any(), any(), any(), any());
    }

    @Test
    void defaultsPlatformToSteamWhenMissing() {
        when(accounts.findAll()).thenReturn(Collections.singletonList(account("a", null)));
        when(client.check(any(), any())).thenReturn(okResult("正常", "Innocent"));

        scheduler.run();

        verify(client).check("a", "steam");
    }
}
