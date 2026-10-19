package com.projtimeline.common;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public final class RequestValues {
    private RequestValues() {}

    public static String requiredText(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) throw BusinessException.badRequest(key + " 不能为空");
        return value.toString().trim();
    }

    public static String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    public static Long longValue(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) return null;
        try { return Long.valueOf(value.toString()); }
        catch (NumberFormatException ex) { throw BusinessException.badRequest(key + " 必须是整数"); }
    }

    public static Integer intValue(Map<String, Object> body, String key) {
        Long value = longValue(body, key);
        if (value == null) return null;
        try { return Math.toIntExact(value); }
        catch (ArithmeticException ex) { throw BusinessException.badRequest(key + " 超出整数范围"); }
    }

    public static BigDecimal decimal(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) return null;
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException ex) { throw BusinessException.badRequest(key + " 必须是数字"); }
    }

    public static LocalDate date(Map<String, Object> body, String key) {
        String value = text(body, key);
        if (value == null) return null;
        try { return LocalDate.parse(value); }
        catch (Exception ex) { throw BusinessException.badRequest(key + " 必须是 YYYY-MM-DD"); }
    }

    public static Boolean bool(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : Boolean.valueOf(value.toString());
    }
}
