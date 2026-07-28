# Group-Call Redis Key Layout Migration

`tagged-v3` is the required group-call Redis key layout for Redis Cluster. It
keeps the call hash and member hash for one group in the same Redis slot.
`legacy` keys cannot provide atomic multi-key transitions in Redis Cluster and
are therefore rejected by the current server.

Changing from `legacy` to `tagged-v3` is a full-stop maintenance migration,
not a rolling deployment. Older binaries do not read the layout marker, so a
marker cannot prevent them from writing legacy keys after the cutover scan.

1. Stop every server running a binary that predates `tagged-v3`; do not leave
   old nodes behind a load balancer or connected to the message bus.
2. Drain or end every legacy group call. Confirm that no keys remain under
   `im:group_call:group:`, `im:group_call:members:v2:`, or the intermediate
   `im:group_call:{state}:` prefixes.
3. Deploy only the current binary with
   `im.call.group.redis-key-layout=tagged-v3`. The conventional environment
   variable is `IM_CALL_GROUP_REDIS_KEY_LAYOUT`.
4. Start the current servers. The first group-call operation records the
   tagged layout marker only after it verifies that legacy state is absent.

Do not run a legacy binary after step 2. If the current server reports that a
legacy key remains or that the layout marker changed, keep group calls stopped,
remove the unexpected legacy state through the normal call-draining workflow,
and retry the cutover. Do not delete live group-call keys to force a cutover.
