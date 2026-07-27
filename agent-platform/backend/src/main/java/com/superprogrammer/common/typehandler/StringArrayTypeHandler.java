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
 * PostgreSQL TEXT[] ↔ List&lt;String&gt;。
 * 用于 memory_tags.aliases 等字符串数组列（写时归一同义别名累积）。
 * 写：createArrayOf("text", String[])；读：getArray → String 列表。
 */
@MappedTypes(List.class)
@MappedJdbcTypes(JdbcType.ARRAY)
public class StringArrayTypeHandler extends BaseTypeHandler<List<String>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType)
            throws SQLException {
        String[] arr = parameter.toArray(new String[0]);
        Array array = ps.getConnection().createArrayOf("text", arr);
        ps.setArray(i, array);
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toList(rs.getArray(columnName));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toList(rs.getArray(columnIndex));
    }

    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toList(cs.getArray(columnIndex));
    }

    private List<String> toList(Array array) throws SQLException {
        if (array == null) {
            return Collections.emptyList();
        }
        Object raw = array.getArray();
        if (raw instanceof Object[]) {
            Object[] objs = (Object[]) raw;
            List<String> result = new ArrayList<>(objs.length);
            for (Object o : objs) {
                if (o == null) {
                    continue;
                }
                result.add(o.toString());
            }
            return result;
        }
        return Collections.emptyList();
    }
}
