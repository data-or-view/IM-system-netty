# im-api — 接口定义层

纯接口与 DTO 模块，定义整个系统的能力边界。这里不依赖 Netty、Redis、MQ、数据库等具体实现。

## 接口清单

### 基础设施
| 接口 | 职责 | 核心方法 |
|------|------|---------|
| `ISessionManager` | 连接会话管理 | bindUser, unbindUser, getByUserId, touch |
| `IRouteTable` | 用户路由表 | register, unregister, lookup, lookupAll |
| `INodeDiscovery` | 节点发现 | register, unregister, getAliveNodes |
| `IClusterMessageBus` | 集群消息总线 | sendToNode, broadcast |
| `IClusterStateStore` | 分布式状态存储 | get, set, delete, watch |
| `IMessageQueue` | 消息队列抽象 | publish, subscribe, unsubscribe |
| `ISequenceManager` | 消息序号生成 | nextSeq, getMaxSeq |
| `IMessageStore` | 消息持久化存储 | save, pullBySeq, pullOffline |
| `ICache<K, V>` | 通用缓存抽象 | get, set, delete, getOrLoad |

### 业务
| 接口 | 职责 | 核心方法 |
|------|------|---------|
| `IAuthenticator` | Token 签发/验证 | issueToken, authenticate |
| `IUserManager` | 用户管理 | register, getUserInformation, getOnlineStatus |
| `IGroupManager` | 群聊管理 | createGroup, disbandGroup, getMemberIds, isMember |
| `IFriendManager` | 好友关系链 | applyAddFriend, deleteFriend, getFriendList |
| `IConversationManager` | 会话管理 | getConversations, markRead, setPinned |
| `ICallManager` | RTC 信令 | createRoom, issueToken |
| `IWebhookManager` | Webhook 回调 | callBefore, callAfter |
| `IMessageRevoke` | 消息撤回 | revoke |
| `IOfflinePush` | 离线推送 | push |
| `IFileStorage` | 文件存储 | upload, delete, getFileUrl |

### DTO
| 类 | 用途 |
|----|------|
| `IMCommand` | 核心消息对象（_cmd, _uid, headers[], body[]） |
| `CommandType` | 命令类型枚举（1~63） |
| `Conversation` | 会话 DTO |
| `UserInformation` | 用户信息 DTO |
| `FriendInformation` | 好友信息 DTO |
| `GroupInformation` | 群信息 DTO |
| `RoomInformation` | RTC 房间 DTO |
| `NodeInformation` | 节点信息 DTO |
| `RouteNode` | 路由节点 DTO |
| `ClusterMessage` | 集群消息 DTO |
| `SignalingAction` | 信令动作枚举 |
| `MultiLoginStrategy` | 多端登录策略枚举 |
| `ImErrorCode` | 错误码枚举 |
| `ImException` | 业务异常类 |
| `MessageQueueTopics` | MQ 主题常量 |

### 内容类型
| 类 | 用途 |
|----|------|
| `IMessageContent` | 消息内容接口 |
| `ContentType` | 内容类型枚举 |
| `TextContent` | 文本消息 |
| `ImageContent` | 图片消息 |
| `FileContent` | 文件消息 |
| `SignalingContent` | 信令消息 |
| `SystemContent` | 系统消息 |

## 边界

- 只放协议枚举、DTO、公共接口和请求/响应抽象。
- 不放 server 启动、handler 组装、DB/Redis/MQ/Netty/MinIO 具体实现。
- 如果公共 API 需要生命周期、异常、校验等通用类型，应保持为稳定公共契约，不能把具体基础设施客户端暴露到签名里。
