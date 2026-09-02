package com.example.feishuproxy.core;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignatureUtilTest {

    /**
     * 黄金向量，独立生成方式如下：
     * {@code printf '' | openssl dgst -sha256 -mac HMAC -macopt "key:<timestamp>\n<secret>" -binary | base64}
     * 它们钉住了两处容易弄反的地方：密钥是 timestamp+"\n"+secret，而被加签的内容为空。
     */
    @Test
    void matchesOpensslReference() {
        assertEquals("z1aykNsdBri0P7KiSJ+x3hbqeiD/GkjlxdkPSUm+0Ks=",
                SignatureUtil.sign(1677384316L, "test"));
        assertEquals("uOj/zKrBjSPfq81oAYXjEqELtgvAgbJ3LWMu7AW6LWg=",
                SignatureUtil.sign(1700000000L, "my-secret-key"));
    }

    @Test
    void producesValidBase64OfSha256Length() {
        byte[] decoded = Base64.getDecoder().decode(SignatureUtil.sign(1700000000L, "any"));
        assertEquals(32, decoded.length);
    }

    @Test
    void changesWithTimestampAndSecret() {
        String base = SignatureUtil.sign(1700000000L, "secret");
        assertNotEquals(base, SignatureUtil.sign(1700000001L, "secret"));
        assertNotEquals(base, SignatureUtil.sign(1700000000L, "secret2"));
    }

    @Test
    void handlesEmptySecret() {
        assertTrue(SignatureUtil.sign(1700000000L, "").length() > 0);
    }
}
