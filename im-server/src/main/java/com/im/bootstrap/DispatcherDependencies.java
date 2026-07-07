package com.im.bootstrap;

import com.im.core.ratelimit.RateLimiter;

record DispatcherDependencies(String nodeId,
                              RuntimeDependencies runtime,
                              ClusterDependencies cluster,
                              BusinessDependencies business,
                              StorageDependencies storage,
                              CallDependencies call,
                              RateLimiter rateLimiter) {
}
