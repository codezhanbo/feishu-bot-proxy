package com.example.feishuproxy.web;

import com.example.feishuproxy.config.FeishuProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 中转服务自身的可选共享令牌鉴权。当 {@code feishu.access-token} 为空时关闭，
 * 这对内网部署是合理的默认值。
 */
@Component
public class ApiTokenInterceptor implements HandlerInterceptor {

    static final String HEADER = "X-Api-Token";

    private final FeishuProperties properties;

    public ApiTokenInterceptor(FeishuProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String expected = properties.getAccessToken();
        if (expected == null || expected.isEmpty()) {
            return true;
        }

        String provided = request.getHeader(HEADER);
        if (provided != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":40100,\"msg\":\"unauthorized\"}");
        return false;
    }
}
