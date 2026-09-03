package com.example.feishuproxy.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 从 {@code feishu.store.*} 构建唯一的 {@link DataSource}（HikariCP 连接池）。
 * <p>
 * 迁移到 MyBatis-Plus 后，数据库成为硬依赖：不再有「store.enabled 关闭 / 无库运行」的降级路径。
 * 因此 {@code feishu.store.jdbc-url} 缺失时这里直接抛异常让应用启动失败，错误信息里提示先执行
 * {@code db/schema.sql}。
 */
@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(FeishuProperties properties) {
        FeishuProperties.Store store = properties.getStore();
        String jdbcUrl = store.getJdbcUrl();
        if (jdbcUrl == null || jdbcUrl.trim().isEmpty()) {
            throw new IllegalStateException(
                    "feishu.store.jdbc-url is required; run db/schema.sql against the target database first");
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl.trim());
        config.setUsername(store.getUsername());
        config.setPassword(store.getPassword());
        // 中转流量受飞书每机器人 5 req/s 的限制，串行化本身无成本；池给个保守上限即可。
        config.setMaximumPoolSize(10);
        config.setPoolName("feishu-store");
        return new HikariDataSource(config);
    }
}
