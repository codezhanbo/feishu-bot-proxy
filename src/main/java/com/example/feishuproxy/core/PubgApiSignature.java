package com.example.feishuproxy.core;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * pubg.hk 开放接口的请求签名。
 * <p>
 * 前端 JS（{@code /assets/main.*.js}）里 {@code window.APISignature} 的做法是：把 URL 上已有的
 * 全部 query 参数收集起来，追加一个 {@code ts}（当前 Unix 秒），按键名字典序排序后拼成
 * {@code k=v&k=v&...}，再拼上固定后缀 {@code &secret=pubghk_api_secret_2024_v1}，最后取整串的
 * MD5（小写十六进制）作为 {@code sign}。
 * <p>
 * 那个「密钥」明文写在前端、任何人都能翻到，所以这个签名只能挡挡随手爬，<strong>不是鉴权</strong>，
 * 别把它当安全边界。这里按原值写死，只为和前端行为保持一致。
 */
public final class PubgApiSignature {

    /** 前端 JS 里硬编码的密钥。本就在明面上，无所谓保密。 */
    private static final String SECRET = "pubghk_api_secret_2024_v1";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private PubgApiSignature() {
    }

    /**
     * 计算签名。
     *
     * @param params           签名前已有的 query 参数（不含 ts/sign）
     * @param timestampSeconds {@code ts}，即当前 Unix 时间戳的秒数
     * @return 32 位小写十六进制 MD5
     */
    public static String sign(Map<String, String> params, long timestampSeconds) {
        // 键名排序用 TreeMap 的自然序，等价于 JS 里 Object.keys().sort() 的字典序。
        Map<String, String> all = new TreeMap<>(params);
        all.put("ts", Long.toString(timestampSeconds));

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : all.entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append('&');
        }
        sb.append("secret=").append(SECRET);
        return md5Hex(sb.toString());
    }

    /**
     * 给一个未签名的 URL 补上 {@code ts} 和 {@code sign}，等价于前端的
     * {@code APISignature.addSignatureToURL}。ts 取当前时间。
     */
    public static String signUrl(String url) {
        long ts = nowSeconds();
        String sign = sign(parseQuery(url), ts);
        return url + (url.contains("?") ? "&" : "?") + "ts=" + ts + "&sign=" + sign;
    }

    /** 当前 Unix 时间戳（秒），与前端的 {@code Math.floor(Date.now()/1000)} 一致。 */
    public static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    /** 把 URL 里的 query 参数解析成已解码的键值对。同名键重复出现时，后者覆盖前者。 */
    private static Map<String, String> parseQuery(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        int q = url.indexOf('?');
        if (q < 0 || q == url.length() - 1) {
            return params;
        }
        String query = url.substring(q + 1);
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(decode(key), decode(value));
        }
        return params;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            // UTF-8 是 JDK 必定提供的字符集，这里不会走到。
            throw new IllegalStateException("UTF-8 不可用", e);
        }
    }

    private static String md5Hex(String input) {
        byte[] digest = md5(input.getBytes(StandardCharsets.UTF_8));
        char[] out = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int v = digest[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    private static byte[] md5(byte[] input) {
        try {
            return MessageDigest.getInstance("MD5").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // MD5 是 JDK 必定提供的算法，这里不会走到。
            throw new IllegalStateException("MD5 不可用", e);
        }
    }
}
