package com.example.feishuproxy.store;

import com.example.feishuproxy.config.FeishuProperties;
import com.example.feishuproxy.model.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * 「我的账号」的后端存储，与 {@link AlertRuleRepository} 共用同一套 {@code feishu.store.*} 连接。
 * <p>
 * 账号是低频配置——只在后台增删改、以及封禁查询命中的时候读写，所以<b>每次操作独立开连接</b>，
 * 复用「读失败返回 null、写失败抛 {@link IllegalStateException}」的语义约定。唯一的例外是
 * {@link #updateFromCheck}：它由封禁查询触发，属于查询的旁路，失败只记日志、不抛异常。
 */
@Component
public class AccountRepository {

    private static final Logger log = LoggerFactory.getLogger(AccountRepository.class);

    private static final String DDL =
            "CREATE TABLE IF NOT EXISTS account ("
                    + "account_id TEXT PRIMARY KEY,"
                    + "ban_status TEXT NOT NULL DEFAULT '正常',"
                    + "level TEXT,"
                    + "last_checked_at TEXT,"
                    + "total_matches BIGINT)";

    private static final String COLUMNS = "account_id, ban_status, level, last_checked_at, total_matches";

    /** 封禁查询自动更新时，把上游的 banType 归一成这两个字面量，与表默认值一致。 */
    public static final String NORMAL = "正常";
    public static final String BANNED = "封禁";

    private final boolean configured;

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public AccountRepository(FeishuProperties properties) {
        this.jdbcUrl = properties.getStore().getJdbcUrl();
        this.username = properties.getStore().getUsername();
        this.password = properties.getStore().getPassword();
        this.configured = jdbcUrl != null && !jdbcUrl.trim().isEmpty();
        if (!configured) {
            log.info("account store disabled: no jdbc-url configured");
            return;
        }
        try (Connection connection = open();
             Statement statement = connection.createStatement()) {
            statement.execute(DDL);
        } catch (SQLException e) {
            log.warn("account store unavailable at startup; accounts stay empty until a reload", e);
        }
    }

    /** 全部账号，按 account_id 升序。数据库不可用（或未配置）时返回 {@code null}。 */
    public List<Account> findAll() {
        if (!configured) {
            return null;
        }
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT " + COLUMNS + " FROM account ORDER BY account_id")) {
            List<Account> out = new ArrayList<>();
            while (rs.next()) {
                out.add(read(rs));
            }
            return out;
        } catch (SQLException e) {
            log.warn("failed to load accounts", e);
            return null;
        }
    }

    /** 单个账号。数据库不可用时抛异常；不存在时返回 {@code null}。 */
    public Account find(String accountId) {
        requireConfigured();
        try (Connection connection = open()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM account WHERE account_id = ?")) {
                statement.setString(1, accountId);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? read(rs) : null;
                }
            }
        } catch (SQLException e) {
            throw storeUnavailable(e);
        }
    }

    /** 新增账号。{@code banStatus} 默认「正常」，其余派生字段留空待查询时回填。 */
    public void insert(Account account) {
        requireConfigured();
        try (Connection connection = open()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO account (account_id, ban_status, level, last_checked_at, total_matches)"
                            + " VALUES (?,?,?,?,?)")) {
                statement.setString(1, account.getAccountId());
                statement.setString(2, account.getBanStatus() == null ? NORMAL : account.getBanStatus());
                statement.setString(3, account.getLevel());
                statement.setString(4, account.getLastCheckedAt());
                setNullable(statement, 5, account.getTotalMatches());
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw storeUnavailable(e);
        }
    }

    /** 更新账号的等级（唯一手工维护的字段）。主键 account_id 不可改。 */
    public void updateLevel(String accountId, String level) {
        requireConfigured();
        try (Connection connection = open()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE account SET level = ? WHERE account_id = ?")) {
                statement.setString(1, level);
                statement.setString(2, accountId);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw storeUnavailable(e);
        }
    }

    public void delete(String accountId) {
        requireConfigured();
        try (Connection connection = open()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM account WHERE account_id = ?")) {
                statement.setString(1, accountId);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw storeUnavailable(e);
        }
    }

    /**
     * 封禁查询命中某账号时，回填该账号的封禁状态、等级、总场次与最后查询时间。
     * <p>
     * 这是查询的旁路：数据库不可用或账号不存在（UPDATE 命中 0 行）都静默跳过，绝不抛异常。
     */
    public void updateFromCheck(String accountId, String banStatus, String level,
                                Long totalMatches, String lastCheckedAt) {
        if (!configured) {
            return;
        }
        try (Connection connection = open()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE account SET ban_status = ?, level = ?, total_matches = ?,"
                            + " last_checked_at = ? WHERE account_id = ?")) {
                statement.setString(1, banStatus);
                statement.setString(2, level);
                setNullable(statement, 3, totalMatches);
                statement.setString(4, lastCheckedAt);
                statement.setString(5, accountId);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("failed to update account from ban check (accountId={})", accountId, e);
        }
    }

    private static Account read(ResultSet rs) throws SQLException {
        Account account = new Account();
        account.setAccountId(rs.getString("account_id"));
        account.setBanStatus(rs.getString("ban_status"));
        account.setLevel(rs.getString("level"));
        account.setLastCheckedAt(rs.getString("last_checked_at"));
        account.setTotalMatches(nullableLong(rs, "total_matches"));
        return account;
    }

    private static void setNullable(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private void requireConfigured() {
        if (!configured) {
            throw new IllegalStateException("account store not configured");
        }
    }

    private static IllegalStateException storeUnavailable(SQLException e) {
        return new IllegalStateException("account store unavailable", e);
    }
}