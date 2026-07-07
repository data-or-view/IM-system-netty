package com.im.bootstrap;

record DispatcherDependencies(String nodeId,
                              RuntimeDependencies runtime,
                              ClusterDependencies cluster,
                              BusinessDependencies business,
                              StorageDependencies storage,
                              CallDependencies call) {
}
