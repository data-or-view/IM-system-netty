# 项目路线图 — OpenIM 功能对标

> 基于 OpenIM v3 功能对比，列出本项目尚未实现的功能，按优先级排列。

---

## P1 — 核心缺失功能

### 离线推送 (Offline Push)

**OpenIM**: FCM (Firebase) / APNs (Apple) / HMS (华为) 推送通知，用户离线时通过系统推送触达。

**本项目**: `IOfflinePush` 接口已定义，无实现。`DeliveryConsumer` 中有 TODO 注释但未接线。

**参考**: OpenIM `internal/rpc/msg/` → `push` 模块

---

### ✅ 消息搜索 (Message Search)

**OpenIM**: `POST /msg/search_msg` 按关键字搜索历史消息，返回带发送者/群组/回复等完整元数据。

**本项目**: 已完成实现：
- `MessageMapper` 动态 SQL：`LIKE` 关键字 + `content_type IN (...)` + `send_id` + 时间范围
- `DbMessageStore.searchMessages()`：调 mapper 查询，`limit + 1` 分页判断 hasMore
- `LocalMessageStore.searchMessages()`：内存过滤实现
- `MessageHandler.handleSearch()`：解析 `SearchMessagesParam`，支持 `keyword`/`contentTypeFilter`/`senderId`/`startTime`/`endTime` 过滤
- 注册 `CHAT_SEARCH("chat.search")` 到 `Operation` 枚举和 `IMServer`

**参考**: OpenIM `internal/rpc/msg/search.go`

---

### 消息删除/清除 (Message Delete / Clear)

**OpenIM**:
- `clear_conversation_msg` — 清空指定会话消息
- `user_clear_all_msg` — 清空用户全部消息
- `delete_msgs` — 删除指定 seq 的消息（逻辑删除）
- `delete_msg_phsical_by_seq` — 按 seq 物理删除（管理员）
- `delete_msg_physical` — 按时间物理删除（管理员）

**本项目**: `IMessageStore.deleteMessages()`、`clearConversationMessages()` 等均为接口默认方法，未实现。

**参考**: OpenIM `internal/rpc/msg/delete.go`, `clear.go`

---

## P2 — 重要功能缺失

### ✅ 全员禁言 (Group Mute All)

**OpenIM**: `POST /group/mute_group` + `cancel_mute_group`，群主/管理员可以全员禁言/取消禁言。

**本项目**: 已完成实现：
- `GroupMemberMapper.batchSetMuteEndTime()`：批量更新 `role_level < 100` 的成员的 `mute_end_time`
- `DbGroupManager.muteGroupAll()`：校验操作者角色 >= 100，设置远未来(`253402300799999L`)或 0
- `LocalGroupManager` 对应内存实现
- `GroupHandler.handleMuteAll()` + `IGroupManager.isMemberMuted()` 检查
- `SendMessageUseCase.handleGroupChat()` 中禁言成员发消息被静默丢弃

**参考**: OpenIM `internal/rpc/group/`

---

### 邀请进群 (Invite to Group)

**OpenIM**: `POST /group/invite_user_to_group`，明确的邀请流程，成员可被邀请入群（区别于申请加入）。

**本项目**: `IGroupManager.inviteMembers()` 定义为接口默认方法，未实现。

**参考**: OpenIM `internal/rpc/group/`

---

### ✅ 好友申请管理 (Friend Request Management)

**OpenIM**:
- `get_friend_apply_list` — 收到的申请列表
- `get_self_friend_apply_list` — 发出的申请列表
- `get_designated_friend_apply` — 申请详情
- `get_self_unhandled_apply_count` — 未处理申请数

**本项目**: 已完成实现：
- `getSentFriendApplyList(userId)`：`WHERE from_user_id = ? ORDER BY created_at DESC`
- `getFriendApplyDetail(fromUserId, toUserId)`：需鉴权请求者是申请双方；`WHERE from_user_id = ? AND to_user_id = ?`
- `getUnhandledApplyCount(userId)`：`SELECT COUNT(*) WHERE to_user_id = ? AND handle_result = 0`
- `DbFriendManager`（MyBatis-Plus LambdaQueryWrapper）+ `LocalFriendManager` 双实现
- `FriendHandler` 对应 3 个 handler + `FriendHandler.handle()` switch cases
- 注册 `FRIEND_APPLY_SENT / _DETAIL / _UNHANDLED_COUNT` 到 `Operation` 枚举和 `IMServer`

**参考**: OpenIM `internal/rpc/relation/friend.go`

---

### 增量同步完整化 (Incremental Sync)

**OpenIM**: 基于版本号的增量同步机制覆盖：
- 好友列表增量 (`get_incremental_friends`)
- 群组列表增量 (`get_incremental_join_groups`)
- 群组成员增量 (`get_incremental_group_members`)
- 黑名单增量 (`get_incremental_blacks`)
- 会话增量 (`get_incremental_conversations`)
- 全量拉取 + 版本号校验

**本项目**: 仅实现消息增量同步（`chat.sync`），其余领域无增量同步。

**参考**: OpenIM `internal/rpc/incrversion/`, `internal/rpc/relation/friend.go`, `internal/rpc/group/`, `internal/rpc/conversation/`

---

### 在线状态订阅 (Presence Subscription)

**OpenIM**:
- `subscribe_users_status` — 订阅指定用户的在线状态
- `get_subscribe_users_status` — 获取已订阅用户的状态
- `get_users_online_status` — 直接查询用户状态（实时查询各 msg gateway）

**本项目**: `IUserManager.subscribeOnlineStatus()`、`unsubscribeOnlineStatus()`、`getSubscribedStatus()` 为接口默认方法，未实现。

**参考**: OpenIM `internal/rpc/user/`

---

### 阅后即焚 (Disappearing Messages)

**OpenIM**: 会话级消息销毁：
- `MsgDestructTime` — 销毁时间
- `IsMsgDestruct` — 是否开启
- `BurnDuration` — 焚毁时长（秒）
- `SetConversation` 可配置这些字段
- `ClearUserConversationMsg` 定时清理过期消息

**本项目**: `im_conversations` 表已有 `burn_duration`、`is_msg_destruct`、`msg_destruct_time` 字段，但管理逻辑和定时清理均未实现。

**参考**: OpenIM `internal/rpc/conversation/`

---

## P3 — 增强功能缺失

| 功能 | OpenIM 端点 | 说明 |
|------|-------------|------|
| ~~服务端代理文件上传~~ ✅ | `object/put_object` | `file.upload` 已统一走 `DirectFileTransferUseCase`，对象写入后同步保存文件元数据，后续可继续使用 `file.download.sign` |
| ~~大文件分片上传~~ ✅ | `object/initiate_multipart_upload`、`object/auth_sign`、`object/complete_multipart_upload` | 已完成实现：`IFileStorageService` 分片接口 → `MinioFileStorageService` MinIO SDK 实现 → `DirectFileTransferUseCase` 编排 init/part-sign/proxy-upload/complete/abort，上传会话写 Redis，支持集群任意节点续传；服务端代理模式仍支持 `file.multipart.upload`
| **Markdown 消息** | `MarkdownTextElem` | 消息内容类型，SDK 侧常见需求 |
| **OA 通知消息** | `OANotificationElem` | 系统/业务通知消息类型 |
| **业务通知发送** | `msg/send_business_notification` | 向用户/群发送自定义 key/value 通知 |
| **批量发消息** | `msg/batch_send_msg` | 一条消息发给多用户或全体用户 |
| **通知账号** | `user/add_notification_account` | 系统通知专用账号，用于发送系统消息 |
| **客户端配置管理** | `user/get_user_client_config`、`set_user_client_config` | 服务端按用户覆盖客户端行为（语言、通知等） |
| **运行时配置管理** | `config/get_config`、`set_config` | REST API 动态修改配置，无需重启 |
| **群 @提及 追踪** | `conversation` 的 `group_at_type` 字段 | 会话级别标记群 @ 事件 |
| **私聊标记** | `conversation` 的 `is_private_chat` | 标记会话为私密聊天 |
| **消息状态查询** | `msg/check_msg_is_send_success` | 检查消息是否发送成功 |
| **服务端时间** | `msg/get_server_time` | 获取服务器当前时间 |

---

## P4 — 运营/可观测性缺失

| 功能 | OpenIM 端点 | 说明 |
|------|-------------|------|
| **用户注册统计** | `statistics/user/register` | 注册量、日活统计 |
| **活跃用户统计** | `statistics/user/active` | 消息活跃用户数 |
| **群组创建统计** | `statistics/group/create` | 群创建量 |
| **活跃群组统计** | `statistics/group/active` | 消息活跃群组数 |
| **Prometheus 集成** | `third/prometheus` | 暴露指标 + 服务发现 |
| **SpyEventListener** | 自定义扩展点 | 已有接口定义，无实现接线 |

---

## 交付优先级建议

```
P1 ████████████  — 直接影响用户可用性：离线推送、消息搜索、消息删除
P2 ██████████    — 显著提升产品完整性：全员禁言、邀请进群、增量同步、阅后即焚
P3 ████████      — 增强体验：分片上传、Markdown、通知账号
P4 ██████        — 运营能力：统计、可观测性
```

初次迭代建议从 **P1** 开始逐个击破，优先交付离线推送和消息搜索这两个用户端感知最明显的功能。
