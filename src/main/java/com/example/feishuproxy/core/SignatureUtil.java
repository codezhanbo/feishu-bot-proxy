package com.example.feishuproxy.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * 飞书自定义机器人的签名算法。
 * <p>
 * 注意它把 HMAC 的惯例反过来了：<em>密钥</em>是 {@code timestamp + "\n" + secret}，而被签名的
 * <em>内容</em>是空字符串。飞书会用 19021 拒绝与自身时钟相差超过一小时的时间戳，所以宿主机必须做 NTP 对时。
 */
public final class SignatureUtil {

    private static final String ALGORITHM = "HmacSHA256";

    private SignatureUtil() {
    }

    public static String sign(long timestampSeconds, String secret) {
        String key = timestampSeconds + "\n" + secret;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to compute feishu signature", e);
        }
    }
}
