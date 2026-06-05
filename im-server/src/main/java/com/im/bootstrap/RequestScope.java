package com.im.bootstrap;

public interface RequestScope extends AutoCloseable {
    @Override
    void close();
}
