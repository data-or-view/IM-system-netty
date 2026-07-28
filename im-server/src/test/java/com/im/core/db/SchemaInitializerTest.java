package com.im.core.db;

import com.im.common.exception.DatabasePersistenceException;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchemaInitializerTest {

    @Test
    void autoRejectsExistingUnversionedImSchemaWithoutDdl() {
        RecordingSchemaCatalog catalog = RecordingSchemaCatalog.legacyV11();

        assertThrows(DatabasePersistenceException.class,
                () -> SchemaInitializer.initialize(catalog.dataSource(), "auto"));
        assertFalse(catalog.executedSql().stream()
                        .map(sql -> sql.toUpperCase(Locale.ROOT))
                        .anyMatch(sql -> sql.contains("ALTER TABLE") || sql.contains("DROP INDEX")),
                "auto must not mutate an existing unversioned IM schema");
    }

    private static final class RecordingSchemaCatalog {

        private static final Set<String> LEGACY_TABLES = Set.of(
                "im_users", "im_blacklist", "im_refresh_tokens", "im_friends", "im_friend_requests",
                "im_groups", "im_group_members", "im_group_requests", "im_conversations", "im_messages",
                "im_message_read_states", "im_message_visibility", "im_idempotency_records",
                "im_message_send_failures", "im_objects", "im_sequences", "im_seq_users",
                "im_sync_versions", "im_sync_changes", "im_system_channels", "im_system_messages",
                "im_system_message_inbox");

        private final List<String> executedSql = new ArrayList<>();

        static RecordingSchemaCatalog legacyV11() {
            return new RecordingSchemaCatalog();
        }

        DataSource dataSource() {
            Connection connection = proxy(Connection.class, (ignored, method, args) -> switch (method.getName()) {
                case "createStatement" -> statement();
                case "getMetaData" -> metadata();
                case "getCatalog" -> "im_system";
                case "close" -> null;
                case "isClosed" -> false;
                default -> defaultValue(method.getReturnType());
            });
            return proxy(DataSource.class, (ignored, method, args) -> switch (method.getName()) {
                case "getConnection" -> connection;
                default -> defaultValue(method.getReturnType());
            });
        }

        List<String> executedSql() {
            return List.copyOf(executedSql);
        }

        private Statement statement() {
            return proxy(Statement.class, (ignored, method, args) -> switch (method.getName()) {
                case "execute" -> {
                    executedSql.add((String) args[0]);
                    yield false;
                }
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }

        private DatabaseMetaData metadata() {
            return proxy(DatabaseMetaData.class, (ignored, method, args) -> switch (method.getName()) {
                case "getTables" -> tableRows((String) args[2]);
                case "getColumns" -> columnRows((String) args[2], (String) args[3]);
                case "getIndexInfo" -> indexRows((String) args[2]);
                default -> defaultValue(method.getReturnType());
            });
        }

        private ResultSet tableRows(String pattern) {
            List<Map<String, Object>> rows = LEGACY_TABLES.stream()
                    .filter(table -> "%".equals(pattern) || table.equalsIgnoreCase(pattern))
                    .map(table -> row("TABLE_NAME", table))
                    .toList();
            return resultSet(rows);
        }

        private ResultSet columnRows(String table, String column) {
            if ("im_messages".equalsIgnoreCase(table) && "revoke_role".equalsIgnoreCase(column)) {
                return resultSet(List.of(row("COLUMN_NAME", "revoke_role", "TYPE_NAME", "TINYINT")));
            }
            return resultSet(List.of());
        }

        private ResultSet indexRows(String table) {
            if ("im_messages".equalsIgnoreCase(table)) {
                return resultSet(List.of(row("INDEX_NAME", "uk_client_msg")));
            }
            return resultSet(List.of());
        }

        private static ResultSet resultSet(List<Map<String, Object>> rows) {
            int[] index = {-1};
            return proxy(ResultSet.class, (ignored, method, args) -> switch (method.getName()) {
                case "next" -> ++index[0] < rows.size();
                case "getString" -> String.valueOf(rows.get(index[0]).get(String.valueOf(args[0])));
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }

        private static Map<String, Object> row(String key, Object value, Object... rest) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(key, value);
            for (int i = 0; i < rest.length; i += 2) {
                row.put((String) rest[i], rest[i + 1]);
            }
            return row;
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0F;
            if (type == double.class) return 0D;
            if (type == char.class) return '\0';
            throw new IllegalArgumentException("Unsupported primitive type: " + type);
        }
    }
}
