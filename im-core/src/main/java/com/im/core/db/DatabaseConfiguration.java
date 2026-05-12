package com.im.core.db;

/**
 * 数据库连接配置。
 *
 * <p>从外部传入连接参数，供 {@link MyBatisPlusFactory} 创建数据源和 SqlSessionFactory。</p>
 *
 * <p>当前默认配置指向本地 MySQL 开发环境，生产环境应通过配置文件或环境变量覆盖。</p>
 *
 * <pre>
 * DatabaseConfiguration config = new DatabaseConfiguration.Builder()
 *     .jdbcUrl("jdbc:mysql://localhost:3306/im_system")
 *     .username("root")
 *     .password("password")
 *     .build();
 * </pre>
 */
public class DatabaseConfiguration {

    /** JDBC 连接 URL */
    private final String jdbcUrl;

    /** 数据库用户名 */
    private final String username;

    /** 数据库密码 */
    private final String password;

    /** 连接池大小 */
    private final int maximumPoolSize;

    /** 连接超时时间（毫秒） */
    private final long connectionTimeoutMs;

    /** 空闲超时时间（毫秒） */
    private final long idleTimeoutMs;

    /** 最大存活时间（毫秒） */
    private final long maxLifetimeMs;

    private DatabaseConfiguration(Builder builder) {
        this.jdbcUrl = builder.jdbcUrl;
        this.username = builder.username;
        this.password = builder.password;
        this.maximumPoolSize = builder.maximumPoolSize;
        this.connectionTimeoutMs = builder.connectionTimeoutMs;
        this.idleTimeoutMs = builder.idleTimeoutMs;
        this.maxLifetimeMs = builder.maxLifetimeMs;
    }

    // ── getters ──

    public String getJdbcUrl() { return jdbcUrl; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public int getMaximumPoolSize() { return maximumPoolSize; }
    public long getConnectionTimeoutMs() { return connectionTimeoutMs; }
    public long getIdleTimeoutMs() { return idleTimeoutMs; }
    public long getMaxLifetimeMs() { return maxLifetimeMs; }

    /** 默认的开发环境配置（localhost:3306, 连接池 10） */
    public static DatabaseConfiguration develop() {
        return new Builder()
                .jdbcUrl("jdbc:mysql://localhost:3306/im_system?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true")
                .username("root")
                .password("password")
                .maximumPoolSize(10)
                .build();
    }

    // ── Builder ──

    public static class Builder {
        private String jdbcUrl = "jdbc:mysql://localhost:3306/im_system";
        private String username = "root";
        private String password = "password";
        private int maximumPoolSize = 10;
        private long connectionTimeoutMs = 10_000;
        private long idleTimeoutMs = 600_000;
        private long maxLifetimeMs = 1_800_000;

        public Builder jdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; return this; }
        public Builder username(String username) { this.username = username; return this; }
        public Builder password(String password) { this.password = password; return this; }
        public Builder maximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; return this; }
        public Builder connectionTimeoutMs(long ms) { this.connectionTimeoutMs = ms; return this; }
        public Builder idleTimeoutMs(long ms) { this.idleTimeoutMs = ms; return this; }
        public Builder maxLifetimeMs(long ms) { this.maxLifetimeMs = ms; return this; }

        public DatabaseConfiguration build() {
            return new DatabaseConfiguration(this);
        }
    }
}
