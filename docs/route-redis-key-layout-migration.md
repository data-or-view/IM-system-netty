# Route Redis Key Layout Migration

`tagged-v2` is the required production route key layout. It uses versioned
keys and an encoded per-user Redis hash tag so each user's route hash and
online-platform ZSet are in the same Redis Cluster slot:

```text
im:route:v2:{u-<base64url-user-id>}
im:online:v2:{u-<base64url-user-id>}
im:route-node:v2:<node-id>
```

Configure it with `im.route.redis-key-layout=tagged-v2`. The environment
variable is `IM_ROUTE_REDIS_KEY_LAYOUT`. The server does not dual-write and
rejects legacy layouts, remaining legacy keys, and conflicting
`im:route:key-layout` marker values.

This is a full-stop migration. Do not perform a rolling deployment: older
nodes do not understand the marker and can recreate legacy state after the
new binary has checked Redis.

1. Stop every old application node. Confirm no old node remains connected to
   Redis, the load balancer, or the cluster message bus.
2. Keep all application nodes stopped. Drain existing sessions or wait at
   least 180 seconds for legacy raw `route:*` and `online:*` keys to expire.
3. Continue waiting for at least 210 seconds from the last old-node write so
   legacy raw `route_node:*` reverse indexes also expire.
4. Verify the legacy namespaces are empty on every Redis primary. Use
   cursor-based `SCAN` on each primary for `route:*`, `online:*`, and
   `route_node:*`; do not use a single-node result as cluster-wide evidence.
5. Set `im.route.redis-key-layout=tagged-v2` and start only the new binary.
   The first node records `im:route:key-layout=tagged-v2` only after a second
   legacy-key check. Start the remaining new nodes after the first succeeds.

If startup reports legacy state, a `draining` marker, or another layout
marker, keep all application nodes stopped. Recheck every Redis primary and
resolve the stale deployment or incomplete drain before retrying. Do not
delete live route keys while clients are connected, and do not change the
marker by hand to force startup.
