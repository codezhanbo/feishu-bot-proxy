package com.example.feishuproxy.store.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 把 Java 的 {@code boolean} 映射到数据库的 INTEGER 0/1 列。
 * <p>
 * 历史表的 {@code success} / {@code enabled} 都是 INTEGER（不是 Postgres 的 {@code boolean}），
 * 而 MyBatis 默认的 {@code BooleanTypeHandler} 走 {@code setBoolean}，在 Postgres 的 int4 列上会报
 * 「类型不匹配」。这里显式用 {@code setInt/getInt} 完成 0/1 与 true/false 的互转。
 */
public class BooleanToIntTypeHandler extends BaseTypeHandler<Boolean> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Boolean parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setInt(i, parameter ? 1 : 0);
    }

    @Override
    public Boolean getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getInt(columnName) != 0;
    }

    @Override
    public Boolean getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getInt(columnIndex) != 0;
    }

    @Override
    public Boolean getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getInt(columnIndex) != 0;
    }
}
