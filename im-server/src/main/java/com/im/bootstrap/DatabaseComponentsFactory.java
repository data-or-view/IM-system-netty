package com.im.bootstrap;

import com.im.config.Config;
import com.im.core.db.DatabaseConfiguration;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.SchemaInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DatabaseComponentsFactory {

    private static final Logger log = LoggerFactory.getLogger(DatabaseComponentsFactory.class);

    private static boolean databaseFailed = false;

    private DatabaseComponentsFactory() {
    }

    static void resetDatabaseFailed() {
        databaseFailed = false;
    }

    static void requireDatabaseEnabled(Config config) {
        if (!dbEnabled(config)) {
            throw new IllegalStateException(
                    "Cluster mode requires database. Set im.db.enabled=true and initialize schema.");
        }
    }

    static void initDatabase(Config config) {
        requireAllowedSchemaMode(config);
        String jdbcUrl = config.getString("im.db.jdbc-url").orElse(null);
        DatabaseConfiguration dbConfig = jdbcUrl != null
                ? new DatabaseConfiguration.Builder()
                .jdbcUrl(jdbcUrl)
                .username(config.getString("im.db.username", "root"))
                .password(config.getString("im.db.password", "password"))
                .build()
                : DatabaseConfiguration.develop();
        try {
            MyBatisPlusFactory.init(dbConfig);
            SchemaInitializer.initialize(MyBatisPlusFactory.getDataSource(),
                    config.getString("im.db.schema").orElse("auto"));
            log.info("Database initialized: jdbcUrl={}", dbConfig.getJdbcUrl());
        } catch (Exception e) {
            log.error("Database initialization failed", e);
            databaseFailed = true;
            throw new IllegalStateException("Database initialization failed", e);
        }
    }

    static void requireAllowedSchemaMode(Config config) {
        String schemaMode = config.getString("im.db.schema").orElse("auto").trim();
        if ("rebuild".equalsIgnoreCase(schemaMode) && !BootstrapSecurityChecks.allowsDevDefaults(config)) {
            throw new IllegalStateException(
                    "im.db.schema=rebuild is restricted to explicit local development or testing");
        }
    }

    private static boolean dbEnabled(Config config) {
        if (databaseFailed) return false;
        return config.getBoolean("im.db.enabled").orElse(false);
    }
}
