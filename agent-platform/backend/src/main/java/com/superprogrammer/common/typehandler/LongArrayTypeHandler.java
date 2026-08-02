package com.superprogrammer.common.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PostgreSQL BIGINT[] ↔ List<Long>。
 * 用于 chat_sessions.kb_ids 等 OLTP 数组列（v6 §5.1 检索 scope）。
 * 写：createArrayOf("bigint", Long[])；读：getArray → Long 列表。
 */
@MappedTypes(List.class)
@MappedJdbcTypes(JdbcType.ARRAY)
public class LongArrayTypeHandler extends BaseTypeHandler<List<Long>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<Long> parameter, JdbcType jdbcType)
            throws SQLException {
        Long[] arr = parameter.toArray(new Long[0]);
        Array array = ps.getConnection().createArrayOf("bigint", arr);
        ps.setArray(i, array);
    }

    @Override
    public List<Long> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toList(rs.getArray(columnName));
    }

    @Override
    public List<Long> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toList(rs.getArray(columnIndex));
    }

    @Override
    public List<Long> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toList(cs.getArray(columnIndex));
    }

    private List<Long> toList(Array array) throws SQLException {
        if (array == null) {
            return Collections.emptyList();
        }
        Object raw = array.getArray();
        if (raw instanceof Object[]) {
            Object[] objs = (Object[]) raw;
            List<Long> result = new ArrayList<>(objs.length);
            for (Object o : objs) {
                if (o == null) {
                    continue;
                }
                result.add(((Number) o).longValue());
            }
            return result;
        }
        return Collections.emptyList();
    }
}
