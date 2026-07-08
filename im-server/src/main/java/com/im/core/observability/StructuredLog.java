package com.im.core.observability;

import java.util.Locale;
import java.util.Map;

public final class StructuredLog {

    private StructuredLog() {
    }

    public static String event(String event, Object... keyValues) {
        StringBuilder line = new StringBuilder();
        append(line, LogFields.EVENT, event);
        if (keyValues == null) {
            return line.toString();
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            Object value = keyValues[i + 1];
            if (key != null) {
                append(line, key.toString(), value);
            }
        }
        return line.toString();
    }

    public static String event(String event, Map<String, ?> fields) {
        StringBuilder line = new StringBuilder();
        append(line, LogFields.EVENT, event);
        if (fields != null) {
            for (Map.Entry<String, ?> entry : fields.entrySet()) {
                append(line, entry.getKey(), entry.getValue());
            }
        }
        return line.toString();
    }

    private static void append(StringBuilder line, String key, Object value) {
        if (key == null || key.isBlank() || isBlank(value)) {
            return;
        }
        if (!line.isEmpty()) {
            line.append(' ');
        }
        line.append(key).append('=').append(format(value));
    }

    private static boolean isBlank(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    private static String format(Object value) {
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name().toLowerCase(Locale.ROOT);
        }
        String text = String.valueOf(value);
        if (isBareToken(text)) {
            return text;
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean isBareToken(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!(Character.isLetterOrDigit(ch)
                    || ch == '.' || ch == '_' || ch == '-' || ch == ':'
                    || ch == '/' || ch == '@')) {
                return false;
            }
        }
        return true;
    }
}
