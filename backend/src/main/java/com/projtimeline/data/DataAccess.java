package com.projtimeline.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projtimeline.common.BusinessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.*;

@Component
public class DataAccess {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DataAccess(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> list(String sql, Map<String, ?> params) {
        return jdbc.query(sql, params, (rs, row) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            var meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String key = camel(meta.getColumnLabel(i));
                Object value = rs.getObject(i);
                if (value instanceof Timestamp timestamp) value = timestamp.toLocalDateTime();
                else if (value instanceof Date date) value = date.toLocalDate();
                result.put(key, value);
            }
            return result;
        });
    }

    public List<Map<String, Object>> list(String sql) { return list(sql, Map.of()); }

    public Map<String, Object> one(String sql, Map<String, ?> params, String notFoundMessage) {
        List<Map<String, Object>> rows = list(sql, params);
        if (rows.isEmpty()) throw BusinessException.notFound(notFoundMessage);
        return rows.get(0);
    }

    public Map<String, Object> optional(String sql, Map<String, ?> params) {
        List<Map<String, Object>> rows = list(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int update(String sql, Map<String, ?> params) { return jdbc.update(sql, params); }

    public long insert(String sql, Map<String, ?> params) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(sql, new MapSqlParameterSource(params), holder, new String[]{"id"});
        Number key = holder.getKey();
        if (key == null) throw new IllegalStateException("Insert did not return an id");
        return key.longValue();
    }

    public long count(String sql, Map<String, ?> params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    public Object scalar(String sql, Map<String, ?> params) { return jdbc.queryForObject(sql, params, Object.class); }

    public Object parseJson(Object value) {
        if (value == null || !(value instanceof String text)) return value;
        try { return objectMapper.readValue(text, new TypeReference<Object>() {}); }
        catch (Exception ignored) { return value; }
    }

    private static String camel(String value) {
        StringBuilder result = new StringBuilder();
        boolean upper = false;
        for (char c : value.toCharArray()) {
            if (c == '_') { upper = true; continue; }
            result.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
            upper = false;
        }
        return result.toString();
    }
}
