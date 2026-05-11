# im-core — 核心实现层

im-api 接口的本地内存实现，35+ 个文件。

## 目录结构

```
im-core/src/main/java/com/im/core/
├── auth/          HmacTokenAuthenticator
├── cache/         ConcurrentHashCache, SafeCache
├── call/          LiveKitCallManager
├── conversation/  LocalConversationManager
├── delivery/      DeliveryConsumer, PersistenceConsumer, LocalClusterMessageBus
├── discovery/     LocalNodeDiscovery, LocalRouteTable
├── dispatcher/    MessageRouterHandler, InterceptorChain
├── friend/        LocalFriendManager
├── group/         LocalGroupManager
├── handler/       ChatHandler, LoginHandler, HeartbeatHandler, PullMessageHandler,
│                  ConversationGetHandler, ConversationSetHandler,
│                  ConnectionEventHandler, AuthenticationInterceptor
├── mq/            MemoryMessageQueue
├── push/          LocalOfflinePush
├── revoke/        LocalMessageRevoke
├── seq/           LocalSequenceManager
├── session/       SessionManager, ConnectionSession
├── store/         LocalMessageStore, LocalStateStore
├── storage/       LocalFileStorage
├── user/          LocalUserManager
├── util/          IMExecutors (虚拟线程池 + 定时池)
├── webhook/       LocalWebhookManager
├── CommandSender.java
└── PendingAcknowledgementManager.java
```

## 设计原则

1. **内存实现**：所有数据存于 ConcurrentHashMap，重启即失
2. **接口可替换**：每个 Local* 类实现对应的 I* 接口，构造器支持依赖注入
3. **安全降级**：缓存层（SafeCache）try-catch 所有异常，不向上传播
4. **虚拟线程**：MQ Consumer、Webhook After、并行推送等异步操作使用 JDK 21 虚拟线程
