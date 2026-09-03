package com.example.feishuproxy.config;

import com.example.feishuproxy.web.ApiTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ApiTokenInterceptor apiTokenInterceptor;
    private final AdminSessionInterceptor adminSessionInterceptor;

    public WebConfig(ApiTokenInterceptor apiTokenInterceptor, AdminSessionInterceptor adminSessionInterceptor) {
        this.apiTokenInterceptor = apiTokenInterceptor;
        this.adminSessionInterceptor = adminSessionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // /health 保持开放，让负载均衡器无需令牌即可访问。
        registry.addInterceptor(apiTokenInterceptor)
                .addPathPatterns("/webhook", "/webhook/**", "/admin/**");

        // 后台管理页面 + 接口走会话鉴权（与 X-Api-Token 相互独立）。
        // 登录 / 登出 / 会话查询三个端点本身不拦，否则就没法登录了。
        registry.addInterceptor(adminSessionInterceptor)
                .addPathPatterns("/home.html", "/console.html", "/bots.html", "/alerts.html",
                        "/alert-runs.html", "/alert-logs.html", "/ban-check.html", "/accounts.html", "/console/**")
                .excludePathPatterns("/console/login", "/console/logout", "/console/session");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 根路径直接进登录页。
        registry.addViewController("/").setViewName("redirect:/login.html");
    }
}
