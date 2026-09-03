package com.example.feishuproxy.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.feishuproxy.model.Account;
import com.example.feishuproxy.model.BanCheckResult;
import com.example.feishuproxy.store.mapper.AccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 「我的账号」的后端存储，改用 MyBatis-Plus 的 {@link AccountMapper}。
 * <p>
 * 账号是低频配置——只在后台增删改、以及封禁查询命中的时候读写。语义约定与迁迁移前一致：
 * 「读失败返回 null、写失败抛 {@link IllegalStateException}」；唯一的例外是
 * {@link #updateFromCheck}：它由封禁查询触发，属于查询的旁路，失败只记日志、不抛异常。
 */
@Component
public class AccountRepository {

    private static final Logger log = LoggerFactory.getLogger(AccountRepository.class);

    /** 封禁查询自动更新时，把上游结果归一成这几个字面量，与表默认值一致。 */
    public static final String NORMAL = "正常";
    /** 临时封禁：上游中文 banStatus 含「临时」时归到这里。 */
    public static final String TEMP_BANNED = "临时封禁";
    /** 永久封禁：上游中文 banStatus 含「永久」时归到这里。 */
    public static final String PERM_BANNED = "永久封禁";
    /** 兜底值：上游只给了 banType（非 innocent）而没有中文 banStatus 时用。 */
    public static final String BANNED = "封禁";

    /**
     * 把上游封禁查询结果归一成账号表的状态（正常 / 临时封禁 / 永久封禁 / 封禁兜底）。
     * 优先看中文 {@code banStatus}——含「永久」→永久封禁、含「临时」→临时封禁、含「正常/未封禁」→正常；
     * 上游没给中文值时退回 {@code banType}（innocent → 正常，其余 → 封禁兜底）。
     * 与后台各页面判定一致，供单查（PubgBanController）与批量巡检（BanCheckScheduler）共用。
     */
    public static String toBanStatus(BanCheckResult result) {
        String status = result.getBanStatus();
        if (status != null) {
            String s = status.trim();
            if (s.contains("永久")) {
                return PERM_BANNED;
            }
            if (s.contains("临时")) {
                return TEMP_BANNED;
            }
            if (s.contains("正常") || s.contains("未封禁")) {
                return NORMAL;
            }
        }
        String type = result.getBanType();
        return type != null && !"innocent".equalsIgnoreCase(type) ? BANNED : NORMAL;
    }

    private final AccountMapper mapper;

    public AccountRepository(AccountMapper mapper) {
        this.mapper = mapper;
    }

    /** 全部账号，按 account_id 升序。数据库不可用时返回 {@code null}。 */
    public List<Account> findAll() {
        try {
            return mapper.selectList(new LambdaQueryWrapper<Account>().orderByAsc(Account::getAccountId));
        } catch (Exception e) {
            log.warn("failed to load accounts", e);
            return null;
        }
    }

    /** 单个账号。数据库不可用时抛异常；不存在时返回 {@code null}。 */
    public Account find(String accountId) {
        try {
            return mapper.selectById(accountId);
        } catch (Exception e) {
            throw new IllegalStateException("account store unavailable", e);
        }
    }

    /** 新增账号。{@code banStatus} 默认「正常」，其余派生字段留空待查询时回填。 */
    public void insert(Account account) {
        if (account.getBanStatus() == null) {
            account.setBanStatus(NORMAL);
        }
        try {
            mapper.insert(account);
        } catch (Exception e) {
            throw new IllegalStateException("account store unavailable", e);
        }
    }

    /** 更新账号的平台与等级（两个手工维护字段）。主键 account_id 不可改。 */
    public void updateProfile(String accountId, String level, String platform) {
        try {
            mapper.update(null, new LambdaUpdateWrapper<Account>()
                    .eq(Account::getAccountId, accountId)
                    .set(Account::getLevel, level)
                    .set(Account::getPlatform, platform));
        } catch (Exception e) {
            throw new IllegalStateException("account store unavailable", e);
        }
    }

    public void delete(String accountId) {
        try {
            mapper.deleteById(accountId);
        } catch (Exception e) {
            throw new IllegalStateException("account store unavailable", e);
        }
    }

    /**
     * 封禁查询命中某账号时，回填该账号的封禁状态、等级、总场次与最后查询时间。
     * <p>
     * 这是查询的旁路：数据库不可用或账号不存在（UPDATE 命中 0 行）都静默跳过，绝不抛异常。
     */
    public void updateFromCheck(String accountId, String banStatus, String level,
                                Long totalMatches, String lastCheckedAt) {
        try {
            mapper.update(null, new LambdaUpdateWrapper<Account>()
                    .eq(Account::getAccountId, accountId)
                    .set(Account::getBanStatus, banStatus)
                    .set(Account::getLevel, level)
                    .set(Account::getTotalMatches, totalMatches)
                    .set(Account::getLastCheckedAt, lastCheckedAt));
        } catch (Exception e) {
            log.warn("failed to update account from ban check (accountId={})", accountId, e);
        }
    }
}
