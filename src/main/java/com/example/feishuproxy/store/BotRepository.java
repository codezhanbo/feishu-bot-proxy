package com.example.feishuproxy.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.feishuproxy.config.FeishuProperties;
import com.example.feishuproxy.store.entity.BotEntity;
import com.example.feishuproxy.store.entity.SettingEntity;
import com.example.feishuproxy.store.mapper.BotMapper;
import com.example.feishuproxy.store.mapper.SettingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机器人定义的后端存储，改用 MyBatis-Plus 的 {@link BotMapper} / {@link SettingMapper}。
 * 机器人是低频配置——只在启动、后台增删改、以及每次变更后的 reload 时读写，所以每次操作走
 * HikariCP 连接池里的一次短事务即可，不需要长连接那套复杂逻辑。
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
 * 灌入一次，此后运行时一律以数据库为准，改 yaml 不再生效。建表语句已迁到 {@code db/schema.sql}，
 * 启动前需手工执行。
 */
@Component
public class BotRepository {

    private static final Logger log = LoggerFactory.getLogger(BotRepository.class);

    private static final String DEFAULT_BOT_KEY = "default-bot";

    private final BotMapper botMapper;
    private final SettingMapper settingMapper;

    /** {@code feishu.bots} 的种子，仅当 bot 表为空时灌入一次。 */
    private final Map<String, FeishuProperties.Bot> seeds;
    /** {@code feishu.default-bot} 的种子，仅当 app_setting 里还没设过时写入。 */
    private final String defaultBotSeed;

    public BotRepository(BotMapper botMapper, SettingMapper settingMapper, FeishuProperties properties) {
        this.botMapper = botMapper;
        this.settingMapper = settingMapper;
        this.seeds = properties.getBots();
        this.defaultBotSeed = properties.getDefaultBot();
        try {
            seed();
        } catch (Exception e) {
            log.warn("bot store unavailable at startup; bots stay empty until a reload", e);
        }
    }

    /** 首次启动种子：bot 表为空时灌入 yaml 里的机器人；default-bot 未设时写入 yaml 里的值。 */
    private void seed() {
        Long count = botMapper.selectCount(null);
        if ((count == null || count == 0L) && seeds != null && !seeds.isEmpty()) {
            for (Map.Entry<String, FeishuProperties.Bot> entry : seeds.entrySet()) {
                insert(entry.getKey(), entry.getValue());
            }
            log.info("seeded {} bot(s) from feishu.bots", seeds.size());
        }
        if (getSetting(DEFAULT_BOT_KEY) == null
                && defaultBotSeed != null && !defaultBotSeed.trim().isEmpty()) {
            setSetting(DEFAULT_BOT_KEY, defaultBotSeed.trim());
            log.info("seeded default-bot = {}", defaultBotSeed.trim());
        }
    }

    /** 全部机器人，按 bot_key 排序。数据库不可用时返回 {@code null}。 */
    public Map<String, FeishuProperties.Bot> findAll() {
        try {
            List<BotEntity> entities = botMapper.selectList(
                    new LambdaQueryWrapper<BotEntity>().orderByAsc(BotEntity::getBotKey));
            Map<String, FeishuProperties.Bot> out = new LinkedHashMap<>();
            for (BotEntity entity : entities) {
                out.put(entity.getBotKey(), toBot(entity));
            }
            return out;
        } catch (Exception e) {
            log.warn("failed to load bots", e);
            return null;
        }
    }

    /** 单个机器人。数据库不可用时抛异常；不存在时返回 {@code null}。 */
    public FeishuProperties.Bot find(String botKey) {
        try {
            BotEntity entity = botMapper.selectById(botKey);
            return entity == null ? null : toBot(entity);
        } catch (Exception e) {
            throw new IllegalStateException("bot store unavailable", e);
        }
    }

    public void insert(String botKey, FeishuProperties.Bot bot) {
        try {
            long now = System.currentTimeMillis();
            BotEntity entity = new BotEntity();
            entity.setBotKey(botKey);
            entity.setWebhook(bot.getWebhook());
            entity.setSecret(bot.getSecret() == null ? "" : bot.getSecret());
            entity.setEnabled(bot.isEnabled());
            entity.setKeywords(joinKeywords(bot.getKeywords()));
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            botMapper.insert(entity);
        } catch (Exception e) {
            throw new IllegalStateException("bot store unavailable", e);
        }
    }

    /**
     * 更新一条机器人。用 {@link LambdaUpdateWrapper} 显式列字段，避开「实体里 {@code createdAt}
     * 是基本类型 long、默认 0 会被 NOT_NULL 策略误当成要更新的值」的坑，也避免动到
     * {@code enabled} 时走 {@code BooleanTypeHandler}。
     */
    public void update(String botKey, FeishuProperties.Bot bot) {
        try {
            botMapper.update(null, new LambdaUpdateWrapper<BotEntity>()
                    .eq(BotEntity::getBotKey, botKey)
                    .set(BotEntity::getWebhook, bot.getWebhook())
                    .set(BotEntity::getSecret, bot.getSecret() == null ? "" : bot.getSecret())
                    .set(BotEntity::isEnabled, bot.isEnabled() ? 1 : 0)
                    .set(BotEntity::getKeywords, joinKeywords(bot.getKeywords()))
                    .set(BotEntity::getUpdatedAt, System.currentTimeMillis()));
        } catch (Exception e) {
            throw new IllegalStateException("bot store unavailable", e);
        }
    }

    public void delete(String botKey) {
        try {
            botMapper.deleteById(botKey);
        } catch (Exception e) {
            throw new IllegalStateException("bot store unavailable", e);
        }
    }

    /** 数据库不可用时返回 {@code null}；未设置（或设为空）时返回 {@code ""}。 */
    public String getDefaultBot() {
        try {
            String value = getSetting(DEFAULT_BOT_KEY);
            return value == null ? "" : value;
        } catch (Exception e) {
            log.warn("failed to read default-bot", e);
            return null;
        }
    }

    /** 空串 / null 表示清除默认机器人。 */
    public void setDefaultBot(String botKey) {
        try {
            setSetting(DEFAULT_BOT_KEY, botKey == null ? "" : botKey.trim());
        } catch (Exception e) {
            throw new IllegalStateException("bot store unavailable", e);
        }
    }

    private String getSetting(String key) {
        SettingEntity entity = settingMapper.selectById(key);
        return entity == null ? null : entity.getSettingValue();
    }

    /** 手动 upsert：先 UPDATE，没命中再 INSERT。避开了 Postgres / H2 对 ON CONFLICT 的方言差异。 */
    private void setSetting(String key, String value) {
        SettingEntity entity = new SettingEntity();
        entity.setSettingKey(key);
        entity.setSettingValue(value);
        if (settingMapper.updateById(entity) == 0) {
            settingMapper.insert(entity);
        }
    }

    private static FeishuProperties.Bot toBot(BotEntity entity) {
        FeishuProperties.Bot bot = new FeishuProperties.Bot();
        bot.setWebhook(entity.getWebhook());
        bot.setSecret(entity.getSecret());
        bot.setEnabled(entity.isEnabled());
        bot.setKeywords(splitKeywords(entity.getKeywords()));
        return bot;
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
}
