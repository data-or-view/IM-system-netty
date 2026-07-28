# Cluster Safety Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix every reviewed P1/P2 by making message projection, uploads, query limits, startup configuration, calls, routing, and database evolution correct under retries and multi-node operation.

**Architecture:** MySQL owns durable schema and conversation projection history; Redis owns routing, call state, timeout claims, and rate limits. The release first establishes a managed Version 2 schema, then changes business behavior to rely on its durable projection table. Storage writes move directly from the SDK to MinIO through a size-constrained POST policy; the IM HTTP API only handles small authenticated JSON control requests.

**Tech Stack:** Java 21, Netty, MyBatis-Plus, MySQL 8, Redis/Lettuce Lua scripts, MinIO Java SDK, TypeScript SDK, Vitest/npm, pnpm.

## Global Constraints

- Every production shared state transition is stored in Redis or MySQL and is idempotent.
- `Message.messageSeq` is the conversation ordering value; `sequenceId` remains request/response correlation only.
- `im.db.schema=auto` may create a blank Version 2 database but must never change a legacy IM schema; legacy upgrade requires `im.db.schema=migrate`.
- `migrate` must use a MySQL advisory lock, ordered checksum-protected SQL, a final fingerprint check, and append version metadata only after success.
- Direct upload uses a MinIO POST policy with `content-length-range(fileSize, fileSize)` and completion verifies the stored object before metadata persists.
- Production paths must not introduce local-memory state for routes, online state, call state, idempotency, or message projection.
- All new externally observable limits are configuration-backed: pull 100, sync conversations 20, HTTP JSON body 1 MiB, pull 60/minute, sync 20/minute by default.
- Redis and MySQL integration tests use real services through the existing E2E base; unit fakes remain test-only.

---

## File Structure

| Path | Responsibility |
| --- | --- |
| `im-server/src/main/java/com/im/core/db/SchemaInitializer.java` | Explicit schema mode dispatch, metadata/fingerprint inspection, advisory locking, ordered migration execution. |
| `im-server/src/main/resources/db/schema.sql` | Fresh Version 2 schema, including version metadata and inbound conversation projection table. |
| `im-server/src/main/resources/db/migration/V2__conversation_projection.sql` | Idempotent v1.1-to-v2 DDL, consumed only by `migrate`. |
| `im-server/src/main/java/com/im/core/db/mapper/ConversationProjectionEventMapper.java` | Durable de-duplicated inbound event writes and unread-count queries. |
| `im-server/src/main/java/com/im/core/conversation/DbConversationManager.java` | Transactional monotonic conversation projection and sequence-based unread calculation. |
| `im-server/src/main/java/com/im/core/file/DirectFileTransferUseCase.java` | POST policy issue, stored-object verification, completion cleanup, and disabled new multipart creation. |
| `im-api/src/main/java/com/im/api/IFileStorageService.java` | POST-policy and object-stat storage port contracts. |
| `im-infrastructure/im-infrastructure-storage/src/main/java/com/im/infrastructure/storage/file/MinioFileStorageService.java` | MinIO `PostPolicy` generation and object metadata lookup. |
| `im-server/src/main/java/com/im/core/call/*StateStore.java` | Atomic group reservation/admission and cluster-wide single-call deadline claim contracts. |
| `im-server/src/main/java/com/im/core/redis/RedisRouteTable.java` | Atomic route removal that preserves platform-online state while another binding exists. |

### Task 1: Explicit Version 2 Schema Lifecycle

**Files:**
- Create: `im-server/src/main/resources/db/migration/V2__conversation_projection.sql`
- Modify: `im-server/src/main/resources/db/schema.sql`
- Modify: `im-server/src/main/java/com/im/core/db/SchemaInitializer.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/DatabaseComponentsFactory.java`
- Modify: `im-server/src/main/resources/application.yml`
- Create: `im-server/src/test/java/com/im/core/db/SchemaInitializerTest.java`
- Create: `im-server/src/test/java/com/im/bootstrap/SchemaMigrationE2ETest.java`
- Modify: `im-server/src/test/java/com/im/core/db/MessageStateSchemaTest.java`

**Interfaces:**
- Consumes: `SchemaInitializer.initialize(DataSource, String)` from `DatabaseComponentsFactory`.
- Produces: `none`, `auto`, `migrate`, and local-only `rebuild` modes; a managed `im_schema_versions` Version 2 record; `im_conversation_projection_events` for Task 2.

- [ ] **Step 1: Write failing schema-mode and resource tests**

```java
@Test
void autoRejectsExistingUnversionedImSchemaWithoutDdl() {
    RecordingSchemaCatalog catalog = legacyV11Catalog();
    assertThrows(DatabasePersistenceException.class,
            () -> initializer.initialize(catalog.dataSource(), "auto"));
    assertThat(catalog.executedSql()).doesNotContain("ALTER TABLE");
}

@Test
void v2ResourceDefinesVersionAndProjectionTables() throws Exception {
    String ddl = Files.readString(Path.of("src/main/resources/db/schema.sql"));
    assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS im_schema_versions"));
    assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS im_conversation_projection_events"));
    assertTrue(ddl.contains("uk_conversation_projection_message"));
}
```

- [ ] **Step 2: Run the focused schema tests and verify the baseline failure**

Run: `mvn -pl im-server -am -Dtest=SchemaInitializerTest,MessageStateSchemaTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because the initializer still treats `auto` as lightweight migration and Version 2 tables do not exist.

- [ ] **Step 3: Implement mode parsing, version metadata, and deterministic migration orchestration**

```java
enum SchemaMode { NONE, AUTO, MIGRATE, REBUILD }

private void initializeManaged(Connection connection, SchemaMode mode) throws Exception {
    SchemaCatalog catalog = SchemaCatalog.inspect(connection, IM_TABLE_NAMES);
    switch (mode) {
        case AUTO -> autoBootstrapOrValidate(connection, catalog);
        case MIGRATE -> migrateV11ToV2(connection, catalog);
        case REBUILD -> rebuildV2(connection);
        case NONE -> { }
    }
}

private void autoBootstrapOrValidate(Connection connection, SchemaCatalog catalog) throws Exception {
    if (catalog.hasNoImTables()) {
        executeFreshV2Schema(connection);
        insertVersion(connection, 2, V2_DESCRIPTION, v2Checksum());
        return;
    }
    if (!catalog.hasValidVersion(2, v2Checksum())) {
        throw new IllegalStateException("existing IM schema is unmanaged; run -Dim.db.schema=migrate explicitly");
    }
    requireV2Fingerprint(connection);
}
```

Implement `migrateV11ToV2` with `SELECT GET_LOCK('im-system-schema-migration', 60)`, a `try/finally` `SELECT RELEASE_LOCK(...)`, structural v1.1 recognition before any DDL, ordered resource statements, and `requireV2Fingerprint` before `insertVersion`. Do not call the current `applyLightweightMigrations` from `auto`; fold its known structural changes into the explicit V2 migration. Include `im_schema_versions` and `im_conversation_projection_events` in the fresh schema/table list and rebuild drop order. Reject `rebuild` in `DatabaseComponentsFactory` unless `BootstrapSecurityChecks.allowsDevDefaults(config)` is true.

```sql
CREATE TABLE IF NOT EXISTS im_conversation_projection_events (
    owner_user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(128) NOT NULL,
    message_id VARCHAR(128) NOT NULL,
    message_seq BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (owner_user_id, conversation_id, message_id),
    KEY idx_projection_unread (owner_user_id, conversation_id, message_seq)
) COMMENT='Inbound conversation projection events';
```

- [ ] **Step 4: Add real-MySQL migration coverage**

Use `BaseE2ETest` to create a v1.1 fixture, run `migrate`, assert the projection table/index/version record, rerun it, and assert one Version 2 record. Simulate an interrupted migration by applying the first V2 DDL manually, then rerun `migrate` and assert final fingerprint success. Verify `auto` fails against the untouched v1.1 fixture and leaves `DatabaseMetaData` unchanged.

- [ ] **Step 5: Run schema tests**

Run: `mvn -pl im-server -am -Dtest=SchemaInitializerTest,MessageStateSchemaTest -Dsurefire.failIfNoSpecifiedTests=false test`

Run when MySQL is available: `mvn -pl im-server -am -Dtest=SchemaMigrationE2ETest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: unit tests pass; E2E reports a clean pass or an unavailable MySQL dependency, never falls back to a different database.

- [ ] **Step 6: Commit the schema lifecycle**

```bash
git add im-server/src/main/resources/db im-server/src/main/java/com/im/core/db/SchemaInitializer.java im-server/src/main/java/com/im/bootstrap/DatabaseComponentsFactory.java im-server/src/main/resources/application.yml im-server/src/test/java/com/im/core/db im-server/src/test/java/com/im/bootstrap/SchemaMigrationE2ETest.java
git commit -m "feat: add explicit versioned schema migration"
```

### Task 2: Monotonic Conversation Projection and Exact Unread State

**Files:**
- Create: `im-server/src/main/java/com/im/core/db/entity/ConversationProjectionEventEntity.java`
- Create: `im-server/src/main/java/com/im/core/db/mapper/ConversationProjectionEventMapper.java`
- Modify: `im-server/src/main/java/com/im/core/db/mapper/ConversationMapper.java`
- Modify: `im-server/src/main/java/com/im/core/db/mapper/MessageReadStateMapper.java`
- Modify: `im-server/src/main/java/com/im/core/db/mapper/SeqUserMapper.java`
- Modify: `im-server/src/main/java/com/im/core/conversation/DbConversationManager.java`
- Create: `im-server/src/test/java/com/im/core/conversation/DbConversationManagerProjectionE2ETest.java`

**Interfaces:**
- Consumes: Version 2 `im_conversation_projection_events` from Task 1 and `Message.getMessageSeq()`.
- Produces: `ConversationProjectionEventMapper.insertInboundIfAbsent(...)`, `countUnreadAfter(...)`, and monotonic `ConversationMapper.upsertConversation(...)` for all consumers.

- [ ] **Step 1: Write failing projection tests**

```java
@Test
void outOfOrderReplayCannotRegressConversationPreviewOrMaxSequence() {
    project(inbound("m2", 2, "second"));
    project(inbound("m1", 1, "first"));
    Conversation conversation = manager.getConversation("receiver", CONVERSATION_ID);
    assertEquals(2, conversation.getLastMsgSeq());
    assertEquals("second", conversation.getLastMsgContent());
}

@Test
void duplicateInboundProjectionCountsOnceAndOwnMessageIsNotUnread() {
    project(inbound("m1", 1, "hello"));
    project(inbound("m1", 1, "hello"));
    project(outbound("m2", 2, "reply"));
    assertEquals(1, manager.getUnreadCount("receiver", CONVERSATION_ID));
}
```

- [ ] **Step 2: Run the projection test and verify failure**

Run: `mvn -pl im-server -am -Dtest=DbConversationManagerProjectionE2ETest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `updateOnMessage` currently passes `sequenceId`, overwrites an existing row unconditionally, and derives unread from `max_seq - read_seq`.

- [ ] **Step 3: Add idempotent inbound events and monotonic SQL**

```java
@Insert("INSERT IGNORE INTO im_conversation_projection_events " +
        "(owner_user_id, conversation_id, message_id, message_seq, created_at) " +
        "VALUES (#{ownerUserId}, #{conversationId}, #{messageId}, #{messageSeq}, #{now})")
int insertInboundIfAbsent(String ownerUserId, String conversationId,
                          String messageId, long messageSeq, long now);

@Select("SELECT COUNT(*) FROM im_conversation_projection_events e " +
        "LEFT JOIN im_message_read_states r ON r.user_id=e.owner_user_id AND r.conversation_id=e.conversation_id " +
        "WHERE e.owner_user_id=#{ownerUserId} AND e.conversation_id=#{conversationId} " +
        "AND e.message_seq > COALESCE(r.read_seq, 0)")
long countUnreadAfter(String ownerUserId, String conversationId);
```

Replace the unconditional upsert with one SQL statement whose preview/timestamp fields use `CASE WHEN VALUES(max_seq) > max_seq THEN VALUES(...) ELSE ... END` before assigning `max_seq = GREATEST(max_seq, VALUES(max_seq))`. In `updateOnMessage`, use `msg.getMessageSeq()`, insert an event only for `!isSelf`, advance `SeqUser` with `GREATEST`, and commit once. Replace response unread calculation with mapper counts. Make read-state upsert use `GREATEST(existing.read_seq, requested.read_seq)` after clamping the request to the observed conversation max. Remove `incrementUnread` and `resetUnread` from the read path or retain them only as unused compatibility methods; they must not determine response counts.

- [ ] **Step 4: Extend test coverage for read progression and retried messages**

Add cases for `markRead(..., 1)` after sequences 1 and 2, a repeat `markRead(..., 1)` that does not regress, and the same `messageId` delivered by a retry. Assert the projection-event primary key prevents a second row and the incremental sync change is recorded only after database commit.

- [ ] **Step 5: Run focused projection tests**

Run: `mvn -pl im-server -am -Dtest=DbConversationManagerProjectionE2ETest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS against real MySQL; mark the test as infrastructure-dependent if no MySQL is running.

- [ ] **Step 6: Commit message projection correctness**

```bash
git add im-server/src/main/java/com/im/core/conversation im-server/src/main/java/com/im/core/db/entity im-server/src/main/java/com/im/core/db/mapper im-server/src/test/java/com/im/core/conversation
git commit -m "fix: make conversation projection monotonic"
```

### Task 3: Bounded Message Pull, Sync, and Rate Limits

**Files:**
- Create: `im-server/src/main/java/com/im/core/handler/unified/MessageQueryLimits.java`
- Modify: `im-server/src/main/java/com/im/core/handler/unified/MessageHandler.java`
- Modify: `im-server/src/main/java/com/im/core/store/DbMessageStore.java`
- Modify: `im-server/src/main/java/com/im/core/db/mapper/MessageMapper.java`
- Modify: `im-server/src/main/java/com/im/core/ratelimit/RateLimitPolicy.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/DispatcherFactory.java`
- Modify: `im-server/src/main/resources/application.yml`
- Modify: `im-server/src/test/java/com/im/core/handler/unified/MessageHandlerAccessTest.java`
- Modify: `im-server/src/test/java/com/im/core/ratelimit/RateLimitPolicyTest.java`

**Interfaces:**
- Consumes: `Config`, `IMessageStore.pullBySequence(String, long, long, int)`, and authenticated `ApiRequest.currentUserId()`.
- Produces: `MessageQueryLimits.from(Config)` with `maxPullLimit()` and `maxSyncConversations()`; a SQL range mapper that always receives a bounded `limit`.

- [ ] **Step 1: Write failing handler and mapper tests**

```java
@Test
void pullClampsRequestedLimitBeforeStoreInvocation() {
    RecordingMessageStore store = new RecordingMessageStore();
    handler(store, new MessageQueryLimits(100, 20)).handle(request(CHAT_PULL, Map.of("limit", 9999)));
    assertEquals(100, store.lastLimit);
}

@Test
void syncRejectsMoreThanConfiguredConversationEntries() {
    assertThrows(ValidationException.class,
        () -> handler(store, new MessageQueryLimits(100, 2)).handle(syncRequest(Map.of("a", 0, "b", 0, "c", 0))));
}
```

- [ ] **Step 2: Run the focused tests and verify failure**

Run: `mvn -pl im-server -am -Dtest=MessageHandlerAccessTest,RateLimitPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because requested limits are passed through, sync has no conversation cap, and the SQL range select has no `LIMIT`.

- [ ] **Step 3: Implement limits at validation, persistence, and rate policy layers**

```java
public record MessageQueryLimits(int maxPullLimit, int maxSyncConversations) {
    public int clampPullLimit(int requested) {
        if (requested <= 0) throw new ValidationException("limit must be positive");
        return Math.min(requested, maxPullLimit);
    }
}

@Select("SELECT * FROM im_messages WHERE conversation_id=#{conversationId} " +
        "AND seq BETWEEN #{from} AND #{to} ORDER BY seq ASC LIMIT #{limit}")
List<MessageEntity> selectBySeqRange(String conversationId, long from, long to, int limit);
```

Make `MessageHandler` clamp once per pull/sync request, reject sync maps over the configured count, substitute `Long.MAX_VALUE` for the open ended upper bound, and pass the effective limit through `DbMessageStore` to the mapper. Register the limits in `DispatcherFactory`. Add `chat-pull.user` and `chat-sync.user` rules in `RateLimitPolicy` for `CHAT_PULL` and `CHAT_SYNC`, with YAML defaults of 60/60 seconds and 20/60 seconds.

- [ ] **Step 4: Add exact rule and SQL contract tests**

Assert the rate rule identity is the authenticated user; inspect mapper annotations or execute a database test to prove `LIMIT #{limit}` is present. Add boundary tests for 1, 100, 101, 20 sync entries, and 21 sync entries.

- [ ] **Step 5: Run focused tests**

Run: `mvn -pl im-server -am -Dtest=MessageHandlerAccessTest,RateLimitPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 6: Commit bounded query work**

```bash
git add im-server/src/main/java/com/im/core/handler/unified im-server/src/main/java/com/im/core/store/DbMessageStore.java im-server/src/main/java/com/im/core/db/mapper/MessageMapper.java im-server/src/main/java/com/im/core/ratelimit im-server/src/main/java/com/im/bootstrap/DispatcherFactory.java im-server/src/main/resources/application.yml im-server/src/test/java/com/im/core
git commit -m "fix: bound message pull and sync queries"
```

### Task 4: Storage-Bounded Direct Uploads and Small HTTP Bodies

**Files:**
- Create: `im-api/src/main/java/com/im/api/PresignedPostPolicy.java`
- Create: `im-api/src/main/java/com/im/api/FileObjectStat.java`
- Modify: `im-api/src/main/java/com/im/api/IFileStorageService.java`
- Modify: `im-server/src/main/java/com/im/core/file/DirectFileTransferUseCase.java`
- Modify: `im-server/src/main/java/com/im/core/file/PresignedUploadResult.java`
- Modify: `im-server/src/main/java/com/im/core/handler/unified/FileDirectTransferHandler.java`
- Modify: `im-server/src/main/java/com/im/core/handler/unified/FileMultipartHandler.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/HttpServerBootstrap.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/StorageComponentsFactory.java`
- Modify: `im-infrastructure/im-infrastructure-storage/src/main/java/com/im/infrastructure/storage/file/MinioFileStorageService.java`
- Modify: `im-sdk/src/transport/http.ts`
- Modify: `im-sdk/src/api/file.ts`
- Modify: `im-server/src/test/java/com/im/core/file/DirectFileTransferUseCaseTest.java`
- Modify: `im-server/src/test/java/com/im/core/handler/unified/FileDirectTransferHandlerTest.java`
- Create: `im-sdk/src/transport/http.test.ts`

**Interfaces:**
- Consumes: MinIO `PostPolicy`, `UploadSession`, and the existing authenticated sign/complete operations.
- Produces: `IFileStorageService.presignPostPolicy(String, String, String, long, int)` and `statObject(String, String)`; `PresignedUploadResult` with `method`, `formFields`, and `fileField`; SDK `postObjectForm(...)`.

- [ ] **Step 1: Write failing upload contract tests**

```java
@Test
void signCreatesExactSizePostPolicy() {
    PresignedUploadResult signed = useCase.signSingleUpload("u1", "a.txt", 3, "text/plain", "", "file");
    assertEquals("POST", signed.method());
    assertEquals("file", signed.fileField());
    assertEquals("3", storage.policyMaxBytes);
}

@Test
void completionDeletesObjectWhenActualSizeDiffers() {
    storage.stat = new FileObjectStat(4, "text/plain");
    assertThrows(ImException.class, () -> useCase.completeSingleUpload("u1", signedFileId));
    assertTrue(storage.deleteCalled);
}
```

```ts
it("uploads the returned fields and blob in a POST form", async () => {
  await http.uploadFile("a.txt", new Uint8Array([1, 2, 3]), "text/plain");
  expect(storageFetch).toHaveBeenCalledWith("https://minio.test/upload", expect.objectContaining({ method: "POST" }));
  expect(postedForm.get("file")).toBeInstanceOf(Blob);
});
```

- [ ] **Step 2: Run focused tests and verify failure**

Run: `mvn -pl im-api,im-server -am -Dtest=DirectFileTransferUseCaseTest,FileDirectTransferHandlerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Run: `npm --prefix im-sdk test -- --runInBand`

Expected: FAIL because signing returns `PUT`, completion only calls `exists`, multipart endpoints are still enabled, and the SDK sends object PUTs.

- [ ] **Step 3: Implement the POST policy and completion verification**

```java
public interface IFileStorageService {
    PresignedPostPolicy presignPostPolicy(String bucket, String objectKey, String contentType,
                                          long exactSizeBytes, int expiresSeconds);
    FileObjectStat statObject(String bucket, String objectKey);
}

private FileUploadCompleteResult complete(UploadSession session, String userId) {
    ensureOwner(session, userId);
    FileObjectStat actual = fileStorage.statObject(session.bucket(), session.objectKey());
    if (actual.sizeBytes() != session.fileSize() || !session.contentType().equals(actual.contentType())) {
        fileStorage.delete(session.bucket(), session.objectKey());
        uploadSessionStore.delete(session);
        throw new ImException(ImErrorCode.BAD_REQUEST, "uploaded object does not match upload session");
    }
    // Persist metadata once, delete session only after the metadata save succeeds.
}
```

Use MinIO `PostPolicy` conditions for bucket, key, exact `Content-Type`, and exact `content-length-range`. Rename the constructor/config value to `maxUploadBytes`, preferring `im.file.max-upload-bytes` and reading `im.minio.max-file-size` only as a deprecated fallback. Remove proxy `uploadSingleFile` handling from the public `FILE_UPLOAD` path. Make multipart init/sign/part/complete return `ValidationException` stating the POST upload migration; retain owner-only abort for existing sessions. Reduce `MAX_HTTP_CONTENT_LENGTH` to `1024 * 1024`.

```ts
private async postObjectForm(signed: PostPolicyUploadResponse, body: UploadBody): Promise<void> {
  const form = new FormData();
  for (const [key, value] of Object.entries(signed.formFields)) form.append(key, value);
  form.append(signed.fileField, this.toBlob(body), "upload");
  const response = await this.fetchWithTimeout(signed.uploadUrl, { method: "POST", body: form });
  if (!response.ok) throw new IMHttpError(response.status, `Object storage upload failed: HTTP ${response.status}`);
}
```

- [ ] **Step 4: Add integration-safe cleanup and API compatibility tests**

Update every `IFileStorageService` fake with `presignPostPolicy` and `statObject`. Test missing object, size mismatch, content-type mismatch, retry completion after metadata persistence, and owner mismatch. Assert multipart init/sign return the documented migration error. Add an HTTP bootstrap/adapter test that a body over 1 MiB returns `413` before dispatch. Update SDK type tests to reject the old `headers`/PUT shape.

- [ ] **Step 5: Run focused upload checks**

Run: `mvn -pl im-api,im-server -am -Dtest=DirectFileTransferUseCaseTest,FileDirectTransferHandlerTest,HttpRequestAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Run: `npm --prefix im-sdk test`

Expected: PASS. Run a MinIO-backed upload test separately when MinIO is available; do not replace it with an in-memory production adapter.

- [ ] **Step 6: Commit direct upload hardening**

```bash
git add im-api/src/main/java/com/im/api im-server/src/main/java/com/im/core/file im-server/src/main/java/com/im/core/handler/unified im-server/src/main/java/com/im/bootstrap im-infrastructure/im-infrastructure-storage/src/main/java/com/im/infrastructure/storage/file im-sdk/src im-server/src/test/java/com/im/core/file im-server/src/test/java/com/im/core/handler/unified im-sdk/src/transport/http.test.ts
git commit -m "fix: enforce direct upload size at object storage"
```

### Task 5: Fail Closed for Unspecified Production Environments

**Files:**
- Modify: `im-server/src/main/java/com/im/bootstrap/Main.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/BootstrapSecurityChecks.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/StorageComponentsFactory.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/ServerComponentsFactory.java`
- Modify: `im-server/src/main/resources/application.yml`
- Modify: `im-server/src/test/java/com/im/bootstrap/BootstrapSecurityChecksTest.java`

**Interfaces:**
- Consumes: `Config`, `IM_ENV`, `im.env`, known development credential constants.
- Produces: `BootstrapSecurityChecks.allowsDevDefaults(Config)` that requires an explicit local environment or `im.security.allow-development-defaults=true` and logs that override.

- [ ] **Step 1: Write failing startup security tests**

```java
@Test
void missingEnvironmentDoesNotPermitKnownDevelopmentSecret() {
    Config config = configWithout("im.env", "im.security.allow-development-defaults");
    assertThrows(IllegalStateException.class, () ->
        BootstrapSecurityChecks.requireSafeSecret(config, "im.token.secret",
            BootstrapSecurityChecks.DEFAULT_TOKEN_SECRET, BootstrapSecurityChecks.DEFAULT_TOKEN_SECRET));
}

@Test
void explicitOverridePermitsDefaultAndEmitsWarning() {
    Config config = configOf("im.security.allow-development-defaults", "true");
    assertTrue(BootstrapSecurityChecks.allowsDevDefaults(config));
}
```

- [ ] **Step 2: Run the bootstrap test and verify failure**

Run: `mvn -pl im-server -am -Dtest=BootstrapSecurityChecksTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because the environment helper currently defaults to `dev` and the old override name is accepted silently.

- [ ] **Step 3: Implement explicit environment and credential checks**

```java
static boolean allowsDevDefaults(Config config) {
    if (config.getBoolean("im.security.allow-development-defaults", false)) {
        log.warn("Development defaults explicitly enabled by im.security.allow-development-defaults");
        return true;
    }
    return explicitEnvironment(config).map(LOCAL_ENVS::contains).orElse(false);
}

private static Optional<String> explicitEnvironment(Config config) {
    return config.getString("im.env").or(() -> optional(System.getProperty("im.env")))
        .or(() -> optional(System.getenv("IM_ENV")))
        .map(value -> value.trim().toLowerCase(Locale.ROOT));
}
```

Remove any implicit `dev` default in `Main.loadConfig`. Check JWT, both MinIO credentials, LiveKit API key, and LiveKit secret through the same helper. Document only the full override name in YAML and reject the destructive `rebuild` mode outside explicit local/override conditions in Task 1.

- [ ] **Step 4: Add component wiring tests**

Test that `StorageComponentsFactory` rejects either MinIO default independently and `ServerComponentsFactory` rejects default LiveKit credentials when calls are enabled. Verify `macbook-dev` remains accepted without the override.

- [ ] **Step 5: Run bootstrap tests**

Run: `mvn -pl im-server -am -Dtest=BootstrapSecurityChecksTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 6: Commit startup safety**

```bash
git add im-server/src/main/java/com/im/bootstrap im-server/src/main/resources/application.yml im-server/src/test/java/com/im/bootstrap/BootstrapSecurityChecksTest.java
git commit -m "fix: reject development credentials by default"
```

### Task 6: Atomic Group Call Reservation and Admission

**Files:**
- Create: `im-server/src/main/java/com/im/core/call/GroupCallReservation.java`
- Create: `im-server/src/main/java/com/im/core/call/GroupCallAdmission.java`
- Modify: `im-server/src/main/java/com/im/core/call/GroupCallStateStore.java`
- Modify: `im-server/src/main/java/com/im/core/call/RedisGroupCallStateStore.java`
- Modify: `im-server/src/main/java/com/im/core/call/GroupCallManager.java`
- Modify: `im-server/src/test/java/com/im/core/call/InMemoryGroupCallStateStore.java`
- Modify: `im-server/src/test/java/com/im/core/call/GroupCallManagerTest.java`
- Create: `im-server/src/test/java/com/im/core/call/RedisGroupCallStateStoreE2ETest.java`

**Interfaces:**
- Consumes: group authorization from `IGroupManager` and deterministic room IDs from `IdGenerator`.
- Produces: `GroupCallStateStore.reserve(...)`, `activate(...)`, and `admit(...)`, each atomic at Redis; `GroupCallReservation.created()` and `GroupCallAdmission.joined()` describe retries without rechecking mutable state in Java.

- [ ] **Step 1: Write failing concurrent-behavior tests**

```java
@Test
void simultaneousStartsProduceOneReservationAndOneRoomCreation() throws Exception {
    List<GroupCallSession> sessions = callConcurrently(2, () -> manager.start("u1", "g1", "video"));
    assertEquals(1, sessions.stream().map(GroupCallSession::roomId).distinct().count());
    assertEquals(1, callManager.createRoomCalls.get());
}

@Test
void concurrentJoinNeverExceedsMaximumAndRetryIsIdempotent() throws Exception {
    callConcurrently(8, () -> manager.join(randomMember(), "g1"));
    assertTrue(manager.active("owner", "g1").participantCount() <= 2);
}
```

- [ ] **Step 2: Run group-call tests and verify failure**

Run: `mvn -pl im-server -am -Dtest=GroupCallManagerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL after the concurrency cases are added because reads and `HSETNX`/`HSET` operations are split across commands.

- [ ] **Step 3: Implement Redis reservation and admission scripts**

```java
GroupCallReservation reserve(String groupId, String roomId, String callType,
                             String initiatorUserId, long now);
GroupCallSession activate(String groupId, String roomId, String sfuEndpoint, long now);
GroupCallAdmission admit(String groupId, String userId, int maxParticipants, long now);
```

The reservation Lua script creates a hash in `CREATING` state and an initiator membership entry only when no group key exists; otherwise it returns the existing session. The manager calls `createRoom` only for `created()==true`, then calls `activate`. Make the room ID reserved before the external call and preserve it on stale reservation recovery. The admission script checks an existing member first, then uses `HLEN`/`HSET` inside one script to reject `FULL` without exceeding `maxParticipants`. Implement leave/end with scripts so key deletion and participant changes cannot interleave incorrectly.

- [ ] **Step 4: Update fakes and add Redis E2E script tests**

Make `InMemoryGroupCallStateStore` obey the new contracts only for unit tests. In a real Redis test, fire concurrent `reserve` and `admit` calls from several threads/connections; assert one room ID, bounded participant count, idempotent duplicate join, and `end` wins over a subsequent join.

- [ ] **Step 5: Run group call checks**

Run: `mvn -pl im-server -am -Dtest=GroupCallManagerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Run when Redis is available: `mvn -pl im-server -am -Dtest=RedisGroupCallStateStoreE2ETest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS or explicit dependency-unavailable report.

- [ ] **Step 6: Commit group-call atomicity**

```bash
git add im-server/src/main/java/com/im/core/call im-server/src/test/java/com/im/core/call
git commit -m "fix: make group call state atomic in redis"
```

### Task 7: Atomic Route Removal and Cluster-Owned Single-Call Timeouts

**Files:**
- Modify: `im-server/src/main/java/com/im/core/redis/RedisRouteTable.java`
- Modify: `im-server/src/main/java/com/im/core/call/SingleCallStateStore.java`
- Modify: `im-server/src/main/java/com/im/core/call/RedisSingleCallStateStore.java`
- Modify: `im-server/src/main/java/com/im/core/call/CallStateManager.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/ServerComponentsFactory.java`
- Modify: `im-server/src/main/resources/application.yml`
- Create: `im-server/src/test/java/com/im/core/redis/RedisRouteTableE2ETest.java`
- Create: `im-server/src/test/java/com/im/core/call/RedisSingleCallStateStoreE2ETest.java`
- Create: `im-server/src/test/java/com/im/core/call/CallStateManagerTimeoutTest.java`

**Interfaces:**
- Consumes: current Redis route bindings and `SingleCallSession.STATUS_RINGING`.
- Produces: atomic `removeRoute` platform occupancy logic; `SingleCallStateStore.claimExpiredRinging(long now, int limit)`; every-node `CallStateManager.scanExpiredCalls()`.

- [ ] **Step 1: Write failing multi-binding and deadline tests**

```java
@Test
void removingOnePlatformBindingLeavesAnotherBindingOnline() {
    routes.bind("u1", "web", "s1", "node1");
    routes.bind("u1", "web", "s2", "node2");
    routes.remove("u1", "web", "s1");
    assertTrue(routes.getOnlinePlatforms("u1").contains("web"));
}

@Test
void onlyOneScannerClaimsAnExpiredRingingCall() {
    store.createIfUsersIdle(ringing("room1", deadlineInPast()));
    assertEquals(1, store.claimExpiredRinging(now, 10).size());
    assertTrue(store.claimExpiredRinging(now, 10).isEmpty());
}
```

- [ ] **Step 2: Run the test classes and verify failure**

Run: `mvn -pl im-server -am -Dtest=CallStateManagerTimeoutTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because route removal unconditionally removes platform online state, and call timeout exists only in the creator JVM's `activeCalls` scheduler.

- [ ] **Step 3: Implement Lua-backed state transitions**

```java
public interface SingleCallStateStore {
    // Existing create/accept/end methods atomically add or remove the deadline.
    List<SingleCallSession> claimExpiredRinging(long nowEpochMillis, int limit);
}

void scanExpiredCalls() {
    for (SingleCallSession session : stateStore.claimExpiredRinging(clock.millis(), timeoutBatchSize)) {
        publishTimeoutOnce(session);
    }
}
```

Store `deadlineAt` in the room hash and `roomId -> deadlineAt` in a Redis sorted set. Change create, accept, reject/cancel/hangup, and timeout scripts to update both structures atomically. The claim script uses a bounded `ZRANGEBYSCORE`, verifies `RINGING`, removes the due entry, changes status to a terminal claimed/timeout state, and returns the session data. Remove local timeout authority from `activeCalls`; schedule a short fixed-delay scan on every `CallStateManager` instance and cancel it during shutdown. Use deterministic timeout message IDs derived from `roomId` and recipient so a queue retry cannot duplicate terminal effects.

In `RedisRouteTable`, use one Lua script to delete the requested route binding, inspect remaining bindings for the same `(userId, platform)`, and only `ZREM` online platform state if no such binding remains. Keep registration and heartbeat format compatible with this inspection.

- [ ] **Step 4: Add real Redis race tests**

Run concurrent route removal from two clients with a surviving same-platform binding. For calls, race `acceptBy` and `claimExpiredRinging` at the deadline and assert exactly one terminal result; then construct a manager after the creating manager has shut down and assert that the second manager claims the deadline and publishes one timeout pair.

- [ ] **Step 5: Run routing and call checks**

Run: `mvn -pl im-server -am -Dtest=CallStateManagerTimeoutTest -Dsurefire.failIfNoSpecifiedTests=false test`

Run when Redis is available: `mvn -pl im-server -am -Dtest=RedisRouteTableE2ETest,RedisSingleCallStateStoreE2ETest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS or explicit dependency-unavailable report.

- [ ] **Step 6: Commit routing and call recovery**

```bash
git add im-server/src/main/java/com/im/core/redis/RedisRouteTable.java im-server/src/main/java/com/im/core/call im-server/src/main/java/com/im/bootstrap/ServerComponentsFactory.java im-server/src/main/resources/application.yml im-server/src/test/java/com/im/core/redis im-server/src/test/java/com/im/core/call
git commit -m "fix: recover routes and call timeouts across nodes"
```

### Task 8: Release Validation, Documentation, and Cluster Scenario Coverage

**Files:**
- Modify: `docs/ai-project-guide.md`
- Modify: `README.md`
- Modify: `docs/file-storage.md`
- Modify: `bin/start-cluster.sh`
- Modify: `im-scenario-tests/scenarios/cluster-ha.ts`
- Create: `im-scenario-tests/scenarios/file-upload-policy.ts`
- Modify: `im-scenario-tests/package.json`

**Interfaces:**
- Consumes: Version 2 schema modes, POST-policy upload contract, Redis call deadline scanner, and cross-node routing behavior from Tasks 1-7.
- Produces: operator instructions that specify the one schema owner and scenario coverage for the affected cluster paths.

- [ ] **Step 1: Write failing documentation and scenario assertions**

```ts
test("upload policy rejects a larger direct object and completion leaves no metadata", async () => {
  const signed = await alice.http.post("/api/file/upload/sign", { fileName: "a.txt", fileSize: 3, mimeType: "text/plain" });
  await expect(postToMinio(signed, new Uint8Array(4))).rejects.toThrow();
  await expect(alice.http.post("/api/file/upload/complete", { fileId: signed.fileId })).rejects.toMatchObject({ code: expect.any(Number) });
});
```

- [ ] **Step 2: Run the affected local test gates and record their current state**

Run: `mvn -B test`

Run: `pnpm --dir im-web test:engineering`

Run: `pnpm --dir im-web build`

Run: `npm --prefix im-sdk test`

Expected: all available offline tests pass. Stop and diagnose any new failure before continuing.

- [ ] **Step 3: Document deployment and client compatibility**

Document this exact deployment sequence: select one node, run it with `-Dim.db.schema=migrate` for v1.1 or `auto` for a blank database, wait for Version 2 validation, then start all remaining nodes with `-Dim.db.schema=none`. Document that old raw clients using `PUT`/multipart sign must switch to the SDK's POST-policy upload before server upgrade. Update `start-cluster.sh` comments/defaults to preserve the single schema owner behavior.

- [ ] **Step 4: Add and run real-service scenarios**

Add the upload-policy scenario and extend `cluster-ha` to verify: a disconnected same-platform session does not mark the platform offline, a call started on node 1 times out after node 1 stops while node 2 stays live, and concurrent group joins do not exceed the configured cap. Use actual Redis, MySQL, MinIO, and the configured MQ; no fake service is valid for these cases.

- [ ] **Step 5: Run release verification**

Run: `mvn -B test`

Run: `pnpm --dir im-web test:engineering`

Run: `pnpm --dir im-web build`

Run: `npm --prefix im-sdk test`

Run when infrastructure is available: `pnpm --dir im-scenario-tests scenario:smoke`

Run when infrastructure is available: `pnpm --dir im-scenario-tests scenario:cluster-ha`

Expected: all local gates pass; real-service commands prove the changed cross-node behavior or explicitly identify unavailable service prerequisites.

- [ ] **Step 6: Commit release documentation and scenarios**

```bash
git add docs/ai-project-guide.md README.md docs/file-storage.md bin/start-cluster.sh im-scenario-tests
git commit -m "test: cover cluster safety hardening"
```

## Plan Self-Review

- Spec coverage: Task 1 implements explicit migration and its metadata; Task 2 implements message order/replay/unread; Task 3 implements pull bounds/rate rules; Task 4 implements POST policies, object verification, multipart removal, SDK migration, and HTTP aggregation; Task 5 implements environment credential safety; Task 6 implements group-call atomicity; Task 7 implements route and single-call failover; Task 8 verifies and documents the complete release.
- Placeholder scan: no deferred or unspecified implementation steps are used. All configuration defaults, key interfaces, state transitions, and test commands are named.
- Type consistency: Task 1 creates the table Task 2 consumes. Task 4's `PresignedPostPolicy` and `FileObjectStat` are both produced by `IFileStorageService` and consumed by the use case/SDK contract. Tasks 6 and 7 add state-store operations without changing production ownership from Redis.
