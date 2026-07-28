package com.im.bootstrap;

import com.im.api.IClusterMessageBus;
import com.im.api.INodeDiscovery;
import com.im.api.IRouteTable;

record ClusterDependencies(IRouteTable routeTable,
                           IClusterMessageBus clusterMessageBus,
                           INodeDiscovery nodeDiscovery,
                           String nodeIncarnation) {
}
