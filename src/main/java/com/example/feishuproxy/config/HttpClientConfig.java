package com.example.feishuproxy.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Duration;

@Configuration
public class HttpClientConfig {

    @Bean
    public RestTemplate feishuRestTemplate(RestTemplateBuilder builder, FeishuProperties properties) {
        RestTemplate restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .build();

        // 我们把飞书的响应报文原样回传给调用方，所以 4xx/5xx 不能抛异常：
        // 默认处理器会吞掉我们本应转发出去的报文。
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }
        });
        return restTemplate;
    }

    /**
     * 供 pubg.hk 封禁查询专用：读超时放宽到 60s。
     * <p>
     * pubg.hk 的封禁接口会把该玩家全部对局的 matchIDs（上千条）一并带回，响应很慢，
     * 复用飞书那套 5s 读超时会频繁超时。连接超时也给到 10s，跨网络访问更稳。
     * 与 feishuRestTemplate 一样，4xx/5xx 不抛异常，把上游报文留给 {@code PubgBanClient} 解析。
     */
    @Bean
    public RestTemplate pubgRestTemplate(RestTemplateBuilder builder) {
        RestTemplate restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(10_000))
                .setReadTimeout(Duration.ofMillis(60_000))
                .build();

        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }
        });
        return restTemplate;
    }
}
