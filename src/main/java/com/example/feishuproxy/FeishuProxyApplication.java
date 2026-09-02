package com.example.feishuproxy;

import com.example.feishuproxy.config.FeishuProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(FeishuProperties.class)
public class FeishuProxyApplication {

    public static void main(String[] args) {
        SpringApplication.run(FeishuProxyApplication.class, args);
    }
}
