package com.example.feishuproxy;

import com.example.feishuproxy.config.FeishuProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(FeishuProperties.class)
@EnableScheduling
@MapperScan("com.example.feishuproxy.store.mapper")
public class FeishuProxyApplication {

    public static void main(String[] args) {
        SpringApplication.run(FeishuProxyApplication.class, args);
    }
}
