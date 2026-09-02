package com.example.feishuproxy.store;

import com.example.feishuproxy.config.FeishuProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机器人定义的后端存储，与 {@link MessageLogRepository} 共用同一套 {@code feishu.store.*} 连接
 * （Supabase 同一个库）。机器人是低频配置——只在启动、后台增删改、以及每次变更后的 reload 时读写，
 * 所以<b>每次操作独立开连接</b>，不需要长连接和断线重连那套复杂逻辑。
 * <p>
 * 语义约定（供 {@code BotRegistry} 区分「连不上」与「零个机器人」）：
 * <ul>
 *   <li>{@link #findAll()} 与 {@link #getDefaultBot()}：数据库不可用时返回 {@code null}，
 *       而不是空集合 / 空串。</li>
 *   <li>写方法（insert/update/delete/setDefaultBot）与 {@link #find} 失败时抛
 *       {@link IllegalStateException}，由控制器转成 503。</li>
 * </ul>
 * <p>
 * {@code feishu.bots} / {@code feishu.default-bot} 只作为<b>首次启动的种子</b>：当 {@code bot} 表为空时
 * 灌入一次，此后运行时一律以数据库为准，改 yaml 不再生效。
 */
@Component
public class BotRepository {

    private static final Logger log = LoggerFactory.getLogger(BotRepository.class);

    private static final String BOT_DDL =
            "CREATE TABLE IF NOT EXISTS bot ("
                    + "bot_key TEXT PRIMARY KEY,"
                    + "webhook TEXT NOT NULL,"
                    + "secret TEXT NOT NULL DEFAULT '',"
                    + "enabled INTEGER NOT NULL DEFAULT 1,"
                    + "keywords TEXT NOT NULL DEFAULT '',"
                    + "created_at BIGINT NOT NULL,"
                    + "updated_at BIGINT NOT NULL)";

    private static final String SETTING_DDL =
            "CREATE TABLE IF NOT EXISTS app_setting ("
                    + "setting_key TEXT PRIMARY KEY,"
                    + "setting_value TEXT NOT NULL)";

    private static final String DEFAULT_BOT_KEY = "default-bot";

    private static final String BOT_COLUMNS = "bot_key, webhook, secret, enabled, keywords";

    /** 连接信息是否已配置；未配置时本组件完全不动作。 */
    private final boolean configured;

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public BotRepository(FeishuProperties properties) {
        this.jdbcUrl = properties.getStore().getJdbcUrl();
        this.username = properties.getStore().getUsername();
        this.password = properties.getStore().getPassword();
        this.configured = jdbcUrl != null && !jdbcUrl.trim().isEmpty();
        if (!configured) {
            log.info("bot store disabled: no jdbc-url configured");
            return;
        }
        try {
            initialize(properties.getBots(), properties.getDefaultBot());
        } catch (Exception e) {
            log.warn("bot store unavailable at startup; bots stay empty until a reload", e);
        }
    }

    private void initialize(Map<String, FeishuProperties.Bot> seeds, String defaultBotSeed) throws SQLException {
        try (Connection connection = open()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(BOT_DDL);
                statement.execute(SETTING_DDL);
            }
            seedIfEmpty(connection, seeds, defaultBotSeed);
        }
    }

    /** 首次启动种子：bot 表为空时灌入 yaml 里的机器人；default-bot 未设时写入 yaml 里的值。 */
    private void seedIfEmpty(Connection connection, Map<String, FeishuProperties.Bot> seeds,
                             String defaultBotSeed) throws SQLException {
        boolean empty;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM bot")) {
            empty = !rs.next() || rs.getLong(1) == 0L;
        }
        if (empty && seeds != null && !seeds.isEmpty()) {
            for (Map.Entry<String, FeishuProperties.Bot> entry : seeds.entrySet()) {
                insert(connection, entry.getKey(), entry.getValue());
            }
            log.info("seeded {} bot(s) from feishu.bots", seeds.size());
        }
        if (getSetting(connection, DEFAULT_BOT_KEY) == null
                && defaultBotSeed != null && !defaultBotSeed.trim().isEmpty()) {
            setSetting(connection, DEFAULT_BOT_KEY, defaultBotSeed.trim());
            log.info("seeded default-bot = {}", defaultBotSeed.trim());
        }
    }

    /** 全部机器人，按 bot_key 排序。数据库不可用时返回 {@code null}。 */
    public Map<String, FeishuProperties.Bot> findAll() {
        if (!configured) {
            return null;
        }
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT " + BOT_COLUMNS + " FROM bot ORDER BY bot_key")) {
            Map<String, FeishuProperties.Bot> out = new LinkedHashMap<>();
            while (rs.next()) {
                out.put(rs.getString("bot_key"), read(rs));
            }
            return out;
        } catch (SQLException e) {
            log.warn("failed to load bots", e);
            return null;
        }
    }

    /** 单个机器人。数据库不可用时抛异常；不存在时返回 {@code null}。 */
    public FeishuProperties.Bot find(String botKey) {
        requireConfigured();
        try (Connection connection = open()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + BOT_COLUMNS + " FROM bot WHERE bot_key = ?")) {
                statement.setString(1, botKey);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? read(rs) : null;
                }
            }
        } catch (SQLException e) {
            throw storeUnavailable(e);
        }
    }

    public void insert(String botKey, FeishuProperties.Bot bot) {
        requireConfigured();
        try (Connection connection = open()) {
            insert(connection, botKey, bot);
        } catch (SQLException e) {
            throw storeUnavailable(e);
        }
    }

    public void update(String botKey, FeishuProperties.Bot bot) {
        requireConfigured();
        try (Connection connection = open()) {
            update(connection, botKey, bot);
        } catch (SQLException e) {
            throw storeUnavailable(e);
        }
    }

    public void delete(String botKey) {
        requireConfigured();
        try (Connection connection = open()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM bot WHERE bot_key = ?")) {
                statement.setString(1, botKey);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw storeUnavailable(e);
        }
    }

    /** 数据库不可用时返回 {@code null}；未设置（或设为空）时返回 {@code ""}。 */
    public String getDefaultBot() {
        if (!configured) {
            return null;
        }
        try (Connection connection = open()) {
            String value = getSetting(connection, DEFAULT_BOT_KEY);
            return value == null ? "" : value;
        } catch (SQLException e) {
            log.warn("failed to read default-bot", e);
            return null;
        }
    }

    /** 空串 / null 表示清除默认机器人。 */
    public void setDefaultBot(String botKey) {
        requireConfigured();
        try (Connection connection = open()) {
            setSetting(connection, DEFAULT_BOT_KEY, botKey == null ? "" : botKey.trim());
        } catch (SQLException e) {
            throw storeUnavailable(e);
        }
    }

    private void insert(Connection connection, String botKey, FeishuProperties.Bot bot) throws SQLException {
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO bot (bot_key, webhook, secret, enabled, keywords, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?)")) {
            statement.setString(1, botKey);
            statement.setString(2, bot.getWebhook());
            statement.setString(3, bot.getSecret() == null ? "" : bot.getSecret());
            statement.setInt(4, bot.isEnabled() ? 1 : 0);
            statement.setString(5, joinKeywords(bot.getKeywords()));
            statement.setLong(6, now);
            statement.setLong(7, now);
            statement.executeUpdate();
        }
    }

    private void update(Connection connection, String botKey, FeishuProperties.Bot bot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE bot SET webhook = ?, secret = ?, enabled = ?, keywords = ?, updated_at = ?"
                        + " WHERE bot_key = ?")) {
            statement.setString(1, bot.getWebhook());
            statement.setString(2, bot.getSecret() == null ? "" : bot.getSecret());
            statement.setInt(3, bot.isEnabled() ? 1 : 0);
            statement.setString(4, joinKeywords(bot.getKeywords()));
            statement.setLong(5, System.currentTimeMillis());
            statement.setString(6, botKey);
            statement.executeUpdate();
        }
    }

    private static FeishuProperties.Bot read(ResultSet rs) throws SQLException {
        FeishuProperties.Bot bot = new FeishuProperties.Bot();
        bot.setWebhook(rs.getString("webhook"));
        bot.setSecret(rs.getString("secret"));
        bot.setEnabled(rs.getInt("enabled") != 0);
        bot.setKeywords(splitKeywords(rs.getString("keywords")));
        return bot;
    }

    private static String getSetting(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT setting_value FROM app_setting WHERE setting_key = ?")) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 手动 upsert：先 UPDATE，没命中再 INSERT。避开了 Postgres / H2 对 ON CONFLICT 的方言差异。 */
    private static void setSetting(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE app_setting SET setting_value = ? WHERE setting_key = ?")) {
            update.setString(1, value);
            update.setString(2, key);
            if (update.executeUpdate() == 0) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO app_setting (setting_key, setting_value) VALUES (?, ?)")) {
                    insert.setString(1, key);
                    insert.setString(2, value);
                    insert.executeUpdate();
                }
            }
        }
    }

    private static String joinKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return "";
        }
        return String.join(",", keywords);
    }

    private static List<String> splitKeywords(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private void requireConfigured() {
        if (!configured) {
            throw new IllegalStateException("bot store not configured");
        }
    }

    private static IllegalStateException storeUnavailable(SQLException e) {
        return new IllegalStateException("bot store unavailable", e);
    }
}
