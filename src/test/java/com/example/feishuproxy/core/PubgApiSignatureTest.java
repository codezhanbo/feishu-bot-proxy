package com.example.feishuproxy.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PubgApiSignatureTest {

    /**
     * 黄金向量取自一条真实请求：
     * {@code https://pubg.hk/api/ban/check?player=CN_A1xleft&platform=steam&ts=1788342307&sign=acabc88a00564056481479f2e6ed382e}
     * 命令行独立复算过：
     * {@code printf 'platform=steam&player=CN_A1xleft&ts=1788342307&secret=pubghk_api_secret_2024_v1' | md5sum}
     * 它钉住了两处容易做错的地方：键名要按字典序排序，且后缀是 {@code &secret=...}。
     */
    @Test
    void matchesRealRequest() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("player", "CN_A1xleft");
        params.put("platform", "steam");
        assertEquals("acabc88a00564056481479f2e6ed382e",
                PubgApiSignature.sign(params, 1788342307L));
    }

    /** 键名的插入顺序不应影响结果——签名前要排序，不能依赖调用方给的顺序。 */
    @Test
    void keyOrderDoesNotMatter() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("player", "CN_A1xleft");
        a.put("platform", "steam");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("platform", "steam");
        b.put("player", "CN_A1xleft");
        assertEquals(PubgApiSignature.sign(a, 1788342307L), PubgApiSignature.sign(b, 1788342307L));
    }

    @Test
    void changesWithTimestampAndParam() {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("player", "CN_A1xleft");
        base.put("platform", "steam");
        String sign = PubgApiSignature.sign(base, 1788342307L);
        assertNotEquals(sign, PubgApiSignature.sign(base, 1788342308L));

        Map<String, String> other = new LinkedHashMap<>(base);
        other.put("player", "SomeoneElse");
        assertNotEquals(sign, PubgApiSignature.sign(other, 1788342307L));
    }

    @Test
    void signIsLowercaseHexMd5() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("platform", "steam");
        params.put("player", "CN_A1xleft");
        String sign = PubgApiSignature.sign(params, 1788342307L);
        assertEquals(32, sign.length());
        assertTrue(sign.matches("[0-9a-f]{32}"));
    }

    /** signUrl 补上的 ts/sign，必须和用同一组参数单独调 sign 算出来的完全一致。 */
    @Test
    void signUrlIsSelfConsistent() {
        String signed = PubgApiSignature.signUrl(
                "https://pubg.hk/api/ban/check?player=CN_A1xleft&platform=steam");
        long ts = Long.parseLong(param(signed, "ts"));
        String sign = param(signed, "sign");

        Map<String, String> params = new LinkedHashMap<>();
        params.put("player", "CN_A1xleft");
        params.put("platform", "steam");
        assertEquals(sign, PubgApiSignature.sign(params, ts));
    }

    private static String param(String url, String key) {
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }
}
