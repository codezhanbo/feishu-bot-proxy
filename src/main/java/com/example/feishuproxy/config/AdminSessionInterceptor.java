package com.example.feishuproxy.config;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * 后台管理界面（{@code /console.html}、{@code /console/logs}）的会话鉴权。
 * 与 webhook 的 {@code X-Api-Token} 无关——那是给程序调用的共享令牌，这是给人登录的账号密码。
 */
@Component
public class AdminSessionInterceptor implements HandlerInterceptor {

    /** 登录成功后写进会话的标记。 */
    public static final String AUTH_ATTRIBUTE = "AUTHENTICATED";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute(AUTH_ATTRIBUTE))) {
            return true;
        }

        // 页面请求重定向到登录页，接口请求回 401 JSON——前端据此跳转或报错。
        if (isPageRequest(request)) {
            response.sendRedirect("/login.html");
        } else {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":40100,\"msg\":\"unauthorized\"}");
        }
        return false;
    }

    private static boolean isPageRequest(HttpServletRequest request) {
        return "GET".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().endsWith(".html");
    }
}
