package com.im.bootstrap;

record DispatcherDependencies(String nodeId,
                              ServerComponentsFactory.RuntimeDependencies runtime,
                              ServerComponentsFactory.ClusterDependencies cluster,
                              ServerComponentsFactory.BusinessDependencies business,
                              ServerComponentsFactory.StorageDependencies storage,
                              ServerComponentsFactory.CallDependencies call) {
}
