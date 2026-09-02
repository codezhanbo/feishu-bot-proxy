package com.example.feishuproxy.config;

import com.example.feishuproxy.web.ApiTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ApiTokenInterceptor apiTokenInterceptor;

    public WebConfig(ApiTokenInterceptor apiTokenInterceptor) {
        this.apiTokenInterceptor = apiTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // /health 保持开放，让负载均衡器无需令牌即可访问。
        registry.addInterceptor(apiTokenInterceptor)
                .addPathPatterns("/webhook", "/webhook/**", "/admin/**");
    }
}
