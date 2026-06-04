package com.im.core.db;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.im.common.exception.DatabasePersistenceException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.logging.slf4j.Slf4jImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.im.core.db.mapper.BlacklistMapper;
import com.im.core.db.mapper.ConversationMapper;
import com.im.core.db.mapper.FriendMapper;
import com.im.core.db.mapper.FriendRequestMapper;
import com.im.core.db.mapper.GroupMapper;
import com.im.core.db.mapper.GroupMemberMapper;
import com.im.core.db.mapper.GroupRequestMapper;
import com.im.core.db.mapper.MessageMapper;
import com.im.core.db.mapper.ObjectMapper;
import com.im.core.db.mapper.SequenceMapper;
import com.im.core.db.mapper.SeqUserMapper;
import com.im.core.db.mapper.UserMapper;

import javax.sql.DataSource;

/**
 * MyBatis-Plus 工厂（无 Spring，纯编程式配置）。
 *
 * <p>持有 {@link SqlSessionFactory} 单例，供所有 <code>DbXxxManager</code> 获取
 * {@code SqlSession} 执行数据库操作。</p>
 *
 * <h3>使用方式</h3>
 * <pre>
 * // 启动时初始化一次
 * MyBatisPlusFactory.init(DatabaseConfiguration.develop());
 *
 * // 业务代码中获取 SqlSession
 * try (SqlSession session = MyBatisPlusFactory.openSession()) {
 *     UserMapper mapper = session.getMapper(UserMapper.class);
 *     // ...
 * }
 * </pre>
 */
public final class MyBatisPlusFactory {

    private static final Logger log = LoggerFactory.getLogger(MyBatisPlusFactory.class);
    private static volatile SqlSessionFactory sqlSessionFactory;
    private static volatile HikariDataSource dataSource;

    private MyBatisPlusFactory() {
        // utility class
    }

    /**
     * 初始化 MyBatis-Plus 环境，创建 SqlSessionFactory。
     *
     * <p>该方法幂等：重复调用不会重复创建。如果已初始化，直接返回。</p>
     *
     * @param config 数据库连接配置
     * @throws IllegalStateException 如果初始化失败
     */
    public static void init(DatabaseConfiguration config) {
        if (sqlSessionFactory != null) {
            log.info("MyBatis-Plus already initialized, skip.");
            return;
        }
        synchronized (MyBatisPlusFactory.class) {
            if (sqlSessionFactory != null) return;

            DataSource ds = createDataSource(config);
            sqlSessionFactory = buildSqlSessionFactory(ds);

            log.info("MyBatis-Plus initialized: url={}, poolSize={}",
                    config.getJdbcUrl(), config.getMaximumPoolSize());
        }
    }

    /**
     * 获取 SqlSessionFactory，必须先在启动时调用 {@link #init(DatabaseConfiguration)}。
     *
     * @throws IllegalStateException 未初始化
     */
    public static SqlSessionFactory getSqlSessionFactory() {
        if (sqlSessionFactory == null) {
            throw new DatabasePersistenceException("MyBatis-Plus not initialized. Call MyBatisPlusFactory.init() first.");
        }
        return sqlSessionFactory;
    }

    /**
     * 打开一个 SqlSession（需要手动 close）。
     *
     * <pre>
     * try (SqlSession session = MyBatisPlusFactory.openSession()) {
     *     // do work
     * }
     * </pre>
     */
    public static org.apache.ibatis.session.SqlSession openSession() {
        return getSqlSessionFactory().openSession();
    }

    /**
     * 获取 HikariCP 数据源引用（用于关闭连接池等管理操作）。
     */
    public static HikariDataSource getDataSource() {
        return dataSource;
    }

    /**
     * 关闭连接池，释放资源。应在应用关闭时调用。
     */
    public static void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("Database connection pool closed.");
        }
        sqlSessionFactory = null;
    }

    // ── 私有方法 ──

    private static HikariDataSource createDataSource(DatabaseConfiguration config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.getJdbcUrl());
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword());
        hikariConfig.setMaximumPoolSize(config.getMaximumPoolSize());
        hikariConfig.setConnectionTimeout(config.getConnectionTimeoutMs());
        hikariConfig.setIdleTimeout(config.getIdleTimeoutMs());
        hikariConfig.setMaxLifetime(config.getMaxLifetimeMs());

        // 连接池优化
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");

        dataSource = new HikariDataSource(hikariConfig);
        return dataSource;
    }

    private static SqlSessionFactory buildSqlSessionFactory(DataSource ds) {
        // MyBatis-Plus 配置（继承自 MyBatis Configuration）
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("im-system", new JdbcTransactionFactory(), ds));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLogImpl(Slf4jImpl.class);

        // 分页插件
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        configuration.addInterceptor(interceptor);

        // 注册所有 Mapper
        configuration.addMapper(UserMapper.class);
        configuration.addMapper(FriendMapper.class);
        configuration.addMapper(FriendRequestMapper.class);
        configuration.addMapper(BlacklistMapper.class);
        configuration.addMapper(GroupMapper.class);
        configuration.addMapper(GroupMemberMapper.class);
        configuration.addMapper(GroupRequestMapper.class);
        configuration.addMapper(ConversationMapper.class);
        configuration.addMapper(MessageMapper.class);
        configuration.addMapper(SequenceMapper.class);
        configuration.addMapper(SeqUserMapper.class);
        configuration.addMapper(ObjectMapper.class);

        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }
}
