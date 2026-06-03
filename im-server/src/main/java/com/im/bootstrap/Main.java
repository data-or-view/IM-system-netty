package com.im.bootstrap;

import com.im.config.Config;
import com.im.config.ConfigLoader;
import com.im.config.YamlConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IM Server 启动入口。
 *
 * <p>职责：加载配置 → 初始化数据库 → 启动 IMServer</p>
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        
        Config config = loadConfig();

        // 节点 ID（命令行参数覆盖）
        String nodeId = config.getString("im.node.id", "node-1");
        if (args.length > 0) nodeId = args[0];
        log.info("Starting IM server with nodeId={}", nodeId);

        IMServer server = new IMServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        log.info("Server ready. Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }

    static Config loadConfig() {
        String activeEnv = System.getProperty("im.env");
        if (activeEnv == null || activeEnv.isBlank()) activeEnv = System.getenv("IM_ENV");
        if (activeEnv != null && !activeEnv.isBlank()) {
            ConfigLoader.register(new YamlConfigSource("classpath:application-" + activeEnv.trim() + ".yml", 1));
        }
        return ConfigLoader.load();
    }
}
