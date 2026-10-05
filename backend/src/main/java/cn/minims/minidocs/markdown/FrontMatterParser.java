package cn.minims.minidocs.markdown;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * YAML frontmatter 简易解析（仅支持平台所需的扁平结构）。
 *
 * <pre>
 * ---
 * title: 快速开始
 * date: 2026-01-01
 * tags: [a, b]
 * ---
 * </pre>
 */
public final class FrontMatterParser {

    private FrontMatterParser() {
    }

    public record Parsed(Map<String, Object> data, String body) {
    }

    public static Parsed parse(String content) {
        if (content == null) {
            return new Parsed(Map.of(), "");
        }
        String normalized = content.replace("\r\n", "\n");
        if (!normalized.startsWith("---")) {
            return new Parsed(Map.of(), content);
        }
        int firstLineEnd = normalized.indexOf('\n');
        if (firstLineEnd < 0 || !"---".equals(normalized.substring(0, firstLineEnd).trim())) {
            return new Parsed(Map.of(), content);
        }
        int end = normalized.indexOf("\n---", firstLineEnd);
        if (end < 0) {
            return new Parsed(Map.of(), content);
        }
        String yaml = normalized.substring(firstLineEnd + 1, end + 1);
        int bodyStart = normalized.indexOf('\n', end + 1);
        String body = bodyStart < 0 ? "" : normalized.substring(bodyStart + 1);
        return new Parsed(parseYaml(yaml), body);
    }

    private static Map<String, Object> parseYaml(String yaml) {
        Map<String, Object> data = new LinkedHashMap<>();
        String currentKey = null;
        List<String> currentList = null;

        for (String rawLine : yaml.split("\n")) {
            String line = rawLine;
            if (line.isBlank() || line.trim().startsWith("#")) {
                continue;
            }
            boolean listItem = line.trim().startsWith("- ");
            if (listItem && currentKey != null) {
                if (currentList == null) {
                    currentList = new ArrayList<>();
                    data.put(currentKey, currentList);
                }
                currentList.add(stripQuotes(line.trim().substring(2).trim()));
                continue;
            }
            currentList = null;
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            currentKey = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (value.isEmpty()) {
                data.put(currentKey, null);
                continue;
            }
            if (value.startsWith("[") && value.endsWith("]")) {
                List<String> items = new ArrayList<>();
                for (String item : value.substring(1, value.length() - 1).split(",")) {
                    String trimmed = stripQuotes(item.trim());
                    if (!trimmed.isEmpty()) {
                        items.add(trimmed);
                    }
                }
                data.put(currentKey, items);
                continue;
            }
            data.put(currentKey, stripQuotes(value));
        }
        return data;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /** 读取字符串字段。 */
    public static String str(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? null : String.valueOf(list.get(0));
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /** 读取字符串列表字段（兼容逗号分隔的字符串）。 */
    @SuppressWarnings("unchecked")
    public static List<String> list(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return ((List<Object>) list).stream().map(String::valueOf).map(String::trim)
                    .filter(s -> !s.isEmpty()).toList();
        }
        return java.util.Arrays.stream(String.valueOf(value).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
