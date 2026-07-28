# Route Redis Key Layout Migration

`tagged-v3` is the required production route key layout. It uses versioned
keys and an encoded per-user Redis hash tag so each user's route hash and
online-platform ZSet are in the same Redis Cluster slot:

```text
im:route:v3:{u-<base64url-user-id>}       Hash value: nodeId|expireAt|generation
im:online:v3:{u-<base64url-user-id>}
im:route-node:v3:<node-id>                Set member: userId|platformId:sessionId|generation
```

Configure it with `im.route.redis-key-layout=tagged-v3`. The environment
variable is `IM_ROUTE_REDIS_KEY_LAYOUT`. The server does not dual-write and
does not accept generationless route values or reverse-index members. It
rejects old markers, raw legacy keys, all `v2` route namespaces, malformed
`v3` state, and conflicting `im:route:key-layout` marker values.

The per-user route hash is authoritative. The per-node reverse index is a
derived cross-slot index: registration and renewal add the current generation
after changing the authoritative route, later renewals repair a missed write,
and startup rebuilds the local node index. Node cleanup also scans the
authoritative route hashes so a process failure between the two Redis slots
cannot permanently hide a current route.

This is a full-stop migration. Do not perform a rolling deployment: older
nodes do not understand the marker and can recreate legacy state after the
new binary has checked Redis.

1. Stop every old application node. Confirm no old node remains connected to
   Redis, the load balancer, or the cluster message bus.
2. Keep all application nodes stopped. Drain existing sessions or wait at
   least 180 seconds from the last old-node write for raw `route:*` /
   `online:*` and `im:route:v2:*` / `im:online:v2:*` keys to expire.
3. Continue waiting for at least 210 seconds from the last old-node write so
   raw `route_node:*` and `im:route-node:v2:*` reverse indexes also expire.
4. Verify all six old namespaces are empty on every Redis primary. Use
   cursor-based `SCAN` on each primary; a single-node result is not
   cluster-wide evidence. Also verify no malformed pre-created `v3` route or
   reverse-index data exists.
5. While every application node remains stopped, delete the old
   `im:route:key-layout` marker. This one explicit marker deletion is the
   versioned full-stop cutover; never perform it while an old node can write.
6. Set `im.route.redis-key-layout=tagged-v3` and start only the new binary.
   The first node records `draining-v3`, repeats the old-key and format checks,
   then records `tagged-v3`. Start remaining new nodes only after it succeeds.

If startup reports old state, an old marker, malformed generation data, or a
conflicting marker, keep all application nodes stopped. Recheck every Redis
primary and resolve the stale deployment or incomplete drain before retrying.
A `draining-v3` marker left by an interrupted first-node startup may be resumed
only by the same new binary after the checks pass. Do not delete live route
keys while clients are connected and do not force the marker to `tagged-v3`.
