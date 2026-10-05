package cn.minims.minidocs.common.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 时间工具：服务端统一按 UTC+8 读写与展示。
 */
public final class TimeUtil {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    public static final DateTimeFormatter DISPLAY_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private TimeUtil() {
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /**
     * 解析时间字符串，兼容 ISO8601（T 分隔 / 空格分隔 / 带毫秒）与纯日期。
     */
    public static LocalDateTime parseDateTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim().replace(' ', 'T');
        try {
            return LocalDateTime.parse(value);
        } catch (Exception ignored) {
            // 继续尝试其它格式
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"));
        } catch (Exception ignored) {
            // 继续尝试其它格式
        }
        if (value.length() == 10) {
            return LocalDate.parse(value).atStartOfDay();
        }
        throw new IllegalArgumentException("无法解析时间：" + text);
    }

    public static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();
        if (value.length() > 10) {
            return parseDateTime(value).toLocalDate();
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            // 统一抛 IllegalArgumentException：日期参数来自地址栏，格式错是调用方的 400，
            // 让它冒到通用 Exception 分支会变成「服务端异常 50000」，把输入错误报成服务坏了
            throw new IllegalArgumentException("无法解析日期：" + text);
        }
    }

    public static String format(LocalDateTime time) {
        return time == null ? null : ISO_DATE_TIME.format(time);
    }

    public static String display(LocalDateTime time) {
        return time == null ? "" : DISPLAY_DATE_TIME.format(time);
    }

    public static String format(LocalDate date) {
        return date == null ? null : DATE.format(date);
    }
}
