# Cluster Safety Hardening Design

**Status:** Approved for implementation

**Decision:** This work fixes the repository's P1 and P2 findings without adding a local-memory production fallback. Redis and MySQL remain the shared-state authorities. The approved database policy is explicit, versioned migration; a legacy database is never changed by `im.db.schema=auto`.

## Scope

This design covers eight connected correctness and safety issues:

1. conversation projection uses the message sequence, remains monotonic under replay and out-of-order delivery, and has exact unread semantics;
2. file upload enforces a storage-side size limit and completion verifies the stored object;
3. the Netty HTTP API no longer aggregates large unauthenticated binary bodies;
4. message pull and sync bound both database work and request frequency;
5. unsafe secrets and storage credentials cannot start accidentally outside explicit local development;
6. group calls, route removal, and single-call ringing deadlines are safe across multiple nodes;
7. schema changes use an explicit versioned migration path; and
8. focused unit, database, SDK, and cluster-facing tests prove the new contracts.

This work does not change message payload semantics, replace MySQL or Redis, introduce an ORM migration dependency, or promise automatic repair of an unknown database schema.

## Global Invariants

- Every production shared state transition is stored in Redis or MySQL and is idempotent.
- A message's conversation ordering key is `Message.messageSeq`; `sequenceId` is only a request/response correlation value.
- The greatest sequence wins conversation preview, timestamp, and `max_seq`. Processing an earlier sequence after a greater one cannot regress any of those fields.
- User-visible unread counts are the number of distinct inbound message projections whose sequence is greater than the user's recorded read sequence. Outbound messages and duplicate consumer attempts do not increase the count.
- Direct browser uploads are bounded by the object store policy, not by a client-declared JSON field or by Netty's in-memory aggregator.
- A caller may retry any endpoint or a consumer may receive the same event again without creating an extra unread item, participant, routing state, call completion, or schema version record.

## Conversation Projection

`DbConversationManager.updateOnMessage` passes `messageSeq` to the projection path. The SQL upsert conditionally replaces `attached_info` and `updated_at` only when the incoming sequence is greater than the stored `max_seq`, while `max_seq` itself becomes the greatest value. Identity fields are populated on initial insert and are not overwritten by a stale message.

The projection transaction records each inbound delivery in a durable `im_conversation_projection_events` table before deriving unread state. Its uniqueness key is `(owner_user_id, conversation_id, message_id)` and it stores `message_seq`; an index beginning with `(owner_user_id, conversation_id, message_seq)` supports unread counting. A duplicate insert is harmless. Only an inserted inbound event is eligible to affect the recipient's unread state. The table is retained and pruned with message retention, never by node-local cache expiry.

Read state is monotonic: a read request can advance, never reduce, `read_seq`, and is capped at the conversation's observed maximum. Conversation list and total unread queries count inbound projection events where `message_seq > read_seq`; they do not infer unread messages from all conversation sequence numbers and therefore do not count a sender's own messages. The legacy `unread_count` column is retained for compatibility but is not the authority for response values.

All projection writes, event insertion, and sequence-user advancement occur in one MySQL transaction. The change record for incremental sync is emitted after the transaction commits.

## File Upload and HTTP Limits

Single-object direct upload moves from a pre-signed PUT URL to a MinIO POST policy. The server creates an upload session first, then returns:

```text
fileId, uploadUrl, method=POST, formFields, fileField=file, expiresIn
```

The policy fixes the bucket and object key, binds the requested content type, and includes `content-length-range(fileSize, fileSize)`. File creation first validates that `fileSize` is positive and no greater than `configuredMaxUploadBytes`, so the policy is both per-session exact and globally bounded. The SDK appends the returned form fields and the file blob to `FormData`, posts it directly to object storage, then calls the authenticated completion endpoint. It does not proxy the object through the IM HTTP server.

`IFileStorageService` exposes a storage-side POST-policy result and object metadata lookup. Completion reads actual object metadata and requires its size to equal the session's expected size. If size, content type policy, or optional supplied digest validation fails, it deletes the object and the upload session before returning a validation error. Completion remains idempotent: a completed file ID returns its existing metadata, while an incomplete or expired session cannot be claimed by another user.

The signed multipart PUT path is removed from the public SDK flow because it cannot enforce a storage-side total content-length limit. The server rejects new multipart sign/init requests with a clear migration error rather than issuing an unbounded signed write. Existing incomplete multipart sessions can still be aborted by their owner. The supported large-file path is the bounded POST policy.

`HttpObjectAggregator` is reduced to a JSON API limit of 1 MiB. `/api/file/upload` no longer accepts a binary proxy body; upload-sign and completion remain small authenticated JSON operations. Oversized HTTP requests receive a payload-too-large response before reaching dispatch. The WebSocket frame limit remains independently bounded as it is today.

The configured upload maximum is named `im.file.max-upload-bytes`; compatibility reading of the former proxy-limit setting is allowed only as a configuration fallback and is documented as deprecated. Production defaults must be finite.

## Pull and Sync Limits

The handler validates a positive requested pull limit and clamps it to `im.message.pull.max-limit` (default 100). It also caps the number of conversation entries accepted by `chat.sync` to `im.message.sync.max-conversations` (default 20). The effective limit is passed into the SQL mapper, so `SELECT ... seq BETWEEN ... ORDER BY seq ASC LIMIT #{limit}` limits database rows, not merely the response serialization. An open-ended range is translated to a bounded SQL range before binding.

`RateLimitPolicy` adds authenticated per-user rules for `CHAT_PULL` and `CHAT_SYNC`: 60 pull requests per minute and 20 sync requests per minute by default. Both limits and windows remain configuration-overridable through the existing rate-limit convention.

## Startup Security

An absent `im.env`/`IM_ENV` is not treated as `dev`. It is an unknown, non-local environment. Known development defaults for JWT signing, MinIO access and secret keys, and LiveKit credentials are rejected unless either:

- `im.env` is one of the explicitly enumerated local environments; or
- `im.security.allow-development-defaults=true` is explicitly set.

The override produces a high-severity startup warning naming the override; it does not become a default. An unknown environment never becomes local through omission. `rebuild` schema mode remains a destructive local-development/testing operation and is rejected outside these same explicit conditions.

## Cluster-State Transitions

### Group Calls

The Redis group-call store owns the group-call lifecycle. A Lua reservation script atomically returns `CREATED` or `EXISTING` and writes the initial call state with a TTL. It prevents two nodes from reserving different active calls for the same group. The room name is deterministic from the group call ID. The reserving node creates the LiveKit room only after reservation, then atomically activates the reservation; a short-lived stale `CREATING` reservation can be reclaimed safely.

A separate Lua admission script atomically treats an existing participant as a successful retry, otherwise compares the Redis participant-set cardinality with the configured maximum and inserts only when capacity remains. Leaving and ending calls atomically remove the participant or terminally mark the call before external notification. Group-membership authorization remains in Java before these scripts; the scripts protect concurrency, not authorization.

### Online Routing

Route registration records enough platform information to identify all live bindings for a `(user, platform)` pair. Route removal uses a Lua script that removes the requested binding and removes the platform from the online ZSet only when no binding for that user/platform remains. Multiple devices and repeated disconnect notifications therefore cannot make an online platform falsely offline.

### Single Calls

Ringing call state includes a deadline in a Redis sorted set keyed by call ID. State transitions that accept, reject, cancel, or end a call atomically remove its deadline. Every server node runs the same bounded timeout scanner. A Lua claim script selects due IDs, atomically transitions only still-ringing calls to an expiry claim, and removes their deadlines. The node that receives a claim publishes the normal timeout effect using the call ID as its idempotency key. A simultaneous acceptance and timeout can produce only one terminal transition. Redis TTL remains a cleanup bound, not the mechanism that decides timeout.

## Explicit Versioned Schema Migration

The supported values of `im.db.schema` are `none`, `auto`, `migrate`, and local-only `rebuild`.

`im_schema_versions` is an append-only metadata table with an integer version primary key, description, checksum, and install timestamp. Version 2 is the first managed schema version and contains the current base schema plus the projection-event table and indexes required above.

| Mode | Behavior |
| --- | --- |
| `none` | Performs no DDL and assumes deployment tooling has already prepared the schema. |
| `auto` | On a database with no IM tables, creates the Version 2 schema and writes Version 2 metadata. On any database that contains IM tables but lacks valid Version 2 metadata, fails before mutating user tables and tells the operator to run `migrate`. On a managed Version 2 database, validates the required structural fingerprint and performs no upgrade. |
| `migrate` | Acquires a MySQL advisory migration lock, recognizes only the documented v1.1 structural fingerprint, applies each explicit v1.1-to-v2 step idempotently, validates the final Version 2 fingerprint, then appends the Version 2 metadata record. Unknown, partial, newer, or incompatible schemas fail with a diagnostic and no fallback to `rebuild`. |
| `rebuild` | Drops and recreates IM tables only in explicit local development/testing. It is never a production migration strategy. |

The migration runner releases the advisory lock on all paths. MySQL DDL is not assumed transactional: each step first inspects whether the target structure already exists, applies only the missing compatible change, and records Version 2 only after final validation. A retry after a process interruption is therefore safe. Migration SQL is checked in as ordered, checksum-protected resources; Java only orchestrates detection, locking, execution, and validation.

Existing deployment scripts select one schema-owning node. Other cluster nodes use `none` after the owner has completed `auto` or `migrate`.

## Compatibility

- The message wire representation remains unchanged; only the server-side projection selects the correct existing field.
- The upload-sign response changes from `PUT` plus headers to `POST` plus form fields. The TypeScript SDK is updated in the same change. External raw API clients must adopt the new response contract before upgrading.
- Multipart direct-sign endpoints cease to create new uploads. This is an intentional safety break; clients use the single bounded POST upload workflow.
- Existing v1.1 databases require an explicit deployment with `-Dim.db.schema=migrate`. They are not altered by an ordinary `auto` startup.

## Acceptance Criteria

1. Delivering sequence 2 before sequence 1 leaves the conversation preview, timestamp, and max sequence at sequence 2; duplicate delivery leaves one inbound projection event and one unread message.
2. A user's own sent messages never appear in their unread count, and a monotonic read sequence hides only the relevant inbound projection events.
3. A direct upload policy rejects a file larger than its configured range at object storage. Completion rejects and deletes an object whose actual size differs from the session expectation.
4. A request larger than 1 MiB is rejected by HTTP before application dispatch; normal JSON sign/complete calls continue to work.
5. Pull and sync queries contain SQL `LIMIT` with the server-capped value; over-limit requests and excessive sync conversation maps are rejected or capped deterministically; rate-limit tests cover both operations.
6. Startup with no explicit local environment and a known JWT, MinIO, or LiveKit development default fails. Explicit local mode and the explicit override have tested, logged behavior.
7. Concurrent group-call starts result in one Redis reservation and one active call. Concurrent joins never exceed the configured participant limit. Disconnecting one of several same-platform sessions leaves that platform online.
8. A ringing call is timed out exactly once by any live node after its deadline, including when the creator node has stopped.
9. `auto` bootstraps a blank database, refuses a legacy v1.1 database without changing it, and validates a managed v2 database. `migrate` upgrades a v1.1 fixture, is retry-safe after a simulated partial step, and records Version 2 only after validation.
10. Backend unit tests, SDK tests, frontend engineering tests, and the available real-infrastructure cluster scenarios pass. Any unavailable Redis/MySQL/MinIO/RocketMQ environment is reported rather than replaced by in-memory production wiring.
