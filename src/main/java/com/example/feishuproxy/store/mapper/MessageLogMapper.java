package com.example.feishuproxy.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.feishuproxy.store.entity.MessageLogEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code message_log} 的 Mapper。除 {@link BaseMapper} 的增删改查外，还提供几个带
 * 「逗号包裹 LIKE + ESCAPE」的自定义查询，对应原有裸 JDBC 里的战报回看、分页与最新落库时间。
 */
public interface MessageLogMapper extends BaseMapper<MessageLogEntity> {

    /**
     * 往回翻同 botKey 最近若干条 post 的原文，供战报差分回看。
     * {@code pattern} 由调用方拼成 {@code %,botKey,%}（已转义），两侧逗号避免把
     * {@code ops-group} 里的 {@code ops} 也匹配进去。
     */
    @Select("<script>"
            + "SELECT body FROM message_log"
            + " WHERE (','||bot_keys||',') LIKE #{pattern} ESCAPE '!' AND msg_type = 'post'"
            + " ORDER BY id DESC LIMIT #{limit}"
            + "</script>")
    List<String> selectRecentPostBodies(@Param("pattern") String pattern, @Param("limit") int limit);

    /** 某 botKey 最新一条记录的落库时间（epoch 毫秒）；没有则为 null。 */
    @Select("SELECT created_at FROM message_log"
            + " WHERE (','||bot_keys||',') LIKE #{pattern} ESCAPE '!'"
            + " ORDER BY id DESC LIMIT 1")
    Long selectLastCreatedAt(@Param("pattern") String pattern);

    /** 分页查询，最新在前。动态拼接 botKey / 成功与否 / 关键字 / 时间范围过滤。 */
    @Select("<script>"
            + "SELECT id, created_at, create_datetime, bot_keys, msg_type, title, text_preview, body,"
            + " body_bytes, client_ip, success, code, msg, results,"
            + " stat_date, survival_level, exp_gained, bp_gained, duration"
            + " FROM message_log"
            + "<where>"
            + "  <if test='botKeyPattern != null'> AND (','||bot_keys||',') LIKE #{botKeyPattern} ESCAPE '!'</if>"
            + "  <if test='success != null'> AND success = #{success}</if>"
            + "  <if test='keyword != null'> AND (title LIKE #{keyword} ESCAPE '!' OR text_preview LIKE #{keyword} ESCAPE '!' OR body LIKE #{keyword} ESCAPE '!')</if>"
            + "  <if test='fromEpochMs != null'> AND created_at &gt;= #{fromEpochMs}</if>"
            + "  <if test='toEpochMs != null'> AND created_at &lt;= #{toEpochMs}</if>"
            + "</where>"
            + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<MessageLogEntity> selectPage(@Param("botKeyPattern") String botKeyPattern,
                                      @Param("success") Integer success,
                                      @Param("keyword") String keyword,
                                      @Param("fromEpochMs") Long fromEpochMs,
                                      @Param("toEpochMs") Long toEpochMs,
                                      @Param("limit") int limit,
                                      @Param("offset") int offset);
}
