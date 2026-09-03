package com.example.feishuproxy.web;

import com.example.feishuproxy.model.BanCheckResult;
import com.example.feishuproxy.store.AccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 封禁状态归一：中文 banStatus 三态优先，退回 banType 兜底。 */
class PubgBanControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static BanCheckResult result(String banStatus, String banType) {
        ObjectNode data = MAPPER.createObjectNode();
        if (banStatus != null) {
            data.put("banStatus", banStatus);
        }
        data.put("banType", banType);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("success", true);
        body.set("data", data);
        return BanCheckResult.from(body);
    }

    @Test
    void classifiesByChineseBanStatus() {
        assertEquals(AccountRepository.PERM_BANNED,
                PubgBanController.toBanStatus(result("永久封禁", "Cheater")));
        assertEquals(AccountRepository.TEMP_BANNED,
                PubgBanController.toBanStatus(result("临时封禁", "Cheater")));
        assertEquals(AccountRepository.NORMAL,
                PubgBanController.toBanStatus(result("未封禁", "Innocent")));
        assertEquals(AccountRepository.NORMAL,
                PubgBanController.toBanStatus(result("正常", "Innocent")));
    }

    @Test
    void fallsBackToBanTypeWhenNoChineseKeyword() {
        // 上游只给英文 banType、中文 banStatus 不含关键字时：非 innocent 兜底「封禁」。
        assertEquals(AccountRepository.BANNED,
                PubgBanController.toBanStatus(result("已封禁", "Cheater")));
        assertEquals(AccountRepository.BANNED,
                PubgBanController.toBanStatus(result(null, "Cheater")));
        assertEquals(AccountRepository.NORMAL,
                PubgBanController.toBanStatus(result(null, "Innocent")));
    }
}