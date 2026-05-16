package com.im.core.store;

import com.im.api.IClusterStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本地状态存储（单机开发/测试用）。
 *
 * 基于 ConcurrentHashMap 的内存 KV 存储。
 * 节点重启后数据丢失——生产环境请换 Redis/ETCD 实现。
 */
public class LocalStateStore implements IClusterStateStore {

    private static final Logger log = LoggerFactory.getLogger(LocalStateStore.class);

    private final ConcurrentMap<String, ConcurrentMap<String, String>> store = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CopyOnWriteArrayList<StateChangeListener>> watchers = new ConcurrentHashMap<>();

    @Override
    public void start() {
        log.info("LocalStateStore started");
    }

    @Override
    public void stop() {
        store.clear();
        watchers.clear();
        log.info("LocalStateStore stopped");
    }

    @Override
    public void put(String namespace, String key, String value) {
        store.computeIfAbsent(namespace, k -> new ConcurrentHashMap<>()).put(key, value);
        notifyWatchers(namespace, key, value);
    }

    @Override
    public String get(String namespace, String key) {
        ConcurrentMap<String, String> ns = store.get(namespace);
        return ns != null ? ns.get(key) : null;
    }

    @Override
    public void delete(String namespace, String key) {
        ConcurrentMap<String, String> ns = store.get(namespace);
        if (ns != null) {
            ns.remove(key);
            notifyWatchers(namespace, key, null);
        }
    }

    @Override
    public void watch(String namespace, String keyPrefix, StateChangeListener listener) {
        watchers.computeIfAbsent(namespace, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @Override
    public void unwatch(String namespace, StateChangeListener listener) {
        CopyOnWriteArrayList<StateChangeListener> list = watchers.get(namespace);
        if (list != null) list.remove(listener);
    }

    private void notifyWatchers(String namespace, String key, String newValue) {
        CopyOnWriteArrayList<StateChangeListener> list = watchers.get(namespace);
        if (list == null) return;
        for (StateChangeListener listener : list) {
            try {
                listener.onChange(namespace, key, newValue);
            } catch (Exception e) {
                log.warn("StateChangeListener error", e);
            }
        }
    }
}
