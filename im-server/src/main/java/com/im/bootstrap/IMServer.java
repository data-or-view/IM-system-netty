package com.im.bootstrap;

import com.im.common.lifecycle.Lifecycle;
import com.im.config.Config;

public class IMServer implements Lifecycle {

    private final ServerRuntime runtime;

    static void resetDatabaseFailed() {
        ServerComponentsFactory.resetDatabaseFailed();
    }

    public IMServer(Config config) {
        this(config, ServerComponentsFactory.create(config));
    }

    IMServer(Config config, ServerComponents components) {
        this.runtime = components.runtime();
    }

    @Override
    public void start() throws Exception {
        runtime.start();
    }

    @Override
    public void stop() {
        runtime.stop();
    }
}
