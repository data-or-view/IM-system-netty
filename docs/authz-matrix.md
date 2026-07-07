# Authorization Matrix

这份矩阵是 `Operation.java` 的权限所有权说明，机器可执行版本在
`im-api/src/main/java/com/im/api/AuthzPolicy.java`。

目标不是替代 handler/use case 里的真实鉴权，而是让每个公开协议操作都有明确的资源边界和执行 owner。新增 `Operation` 时必须同步更新 `AuthzPolicy`，否则 `im-api` 测试会失败。

## Scopes

| Scope | 含义 | 典型操作 |
|------|------|----------|
| `PUBLIC` | 不需要登录即可访问。 | 注册、登录 |
| `SELF` | 只能读写调用者自己的数据。 | 我的资料、会话列表、好友列表 |
| `USER_LOOKUP` | 登录用户可查询公开用户资料。 | 用户资料、用户搜索 |
| `FRIEND_RELATION` | 只能操作调用者自己的好友/黑名单关系。 | 删除好友、拉黑、单聊发送 |
| `FRIEND_REQUEST` | 只能创建或处理与自己相关的好友申请。 | 好友申请、审批 |
| `GROUP_MEMBER` | 必须是当前群成员。 | 群资料、群成员、群聊、群通话 |
| `GROUP_MANAGER` | 需要群主或管理员权限。 | 踢人、禁言、审批加群 |
| `GROUP_OWNER` | 只能由当前群主执行。 | 解散群、转让群 |
| `CONVERSATION_MEMBER` | 必须拥有该会话的读取权限。 | 拉取消息、已读、撤回 |
| `FILE_OWNER` | 只能操作自己的上传会话或有权限访问的附件。 | 上传、下载签名、分片上传 |
| `SYSTEM_INBOX` | 只能读取和标记自己的系统收件箱。 | 系统消息列表、已读 |
| `ADMIN` | 需要管理员权限。 | 发布系统消息 |
| `WS_SESSION` | 已建立的 WS 会话级权限。 | 心跳、token 续期 |

## Operation Groups

| 分类 | Scope | 执行 owner |
|------|-------|------------|
| 用户注册/登录 | `PUBLIC` | `UserHandler`、`LoginHandler`、`RegisterHandler` |
| 用户资料 | `SELF` / `USER_LOOKUP` | `AuthInterceptor`、`UserHandler` |
| 好友申请 | `FRIEND_REQUEST` | `FriendApplyPolicy`、`DbFriendManager` |
| 好友关系 | `SELF` / `FRIEND_RELATION` | `FriendHandler`、`DbFriendManager` |
| 群基础操作 | `SELF` / `GROUP_MEMBER` | `GroupHandler`、`DbGroupManager` |
| 群管理操作 | `GROUP_MANAGER` / `GROUP_OWNER` | `GroupHandler`、`DbGroupManager` |
| 群通话 | `GROUP_MEMBER` | `GroupCallHandler`、`GroupCallManager` |
| 会话设置/已读 | `CONVERSATION_MEMBER` | `ConversationHandler`、`ConversationAccessChecker` |
| 消息读/搜/撤回 | `CONVERSATION_MEMBER` | `ConversationAccessChecker`、`RevokeUseCase`、`IMessageStore` |
| 消息发送 | `FRIEND_RELATION` / `GROUP_MEMBER` | `DefaultChatSendPolicy`、`SendMessageUseCase` |
| 文件传输 | `FILE_OWNER` | 文件 handler、`DirectFileTransferUseCase` |
| 系统消息 | `SYSTEM_INBOX` / `ADMIN` | `SystemMessageHandler` |
| WS 会话 | `PUBLIC` / `WS_SESSION` | `LoginHandler`、`RegisterHandler`、`HeartbeatHandler` |

## Gate Commands

```bash
mvn -pl im-api -am -Dtest=OperationContractTest,AuthzPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

CI 的后端 job 还会跑非 E2E Maven 测试，确保矩阵、协议 contract 和服务端单元测试一起通过。
