# IM Web 前端页面设计

## 概述

为 IM Web 前端新增页面级组件：创建群、群详情/成员管理、用户个人信息，并增强现有聊天页面。引入 react-router-dom 实现页面路由。

## 路由结构

```
/login                  → LoginPage (现有)
/chat                   → ChatLayout (现有，作为外层容器)
  /chat                 → ChatArea (默认页，现有增强)
  /chat/create-group    → CreateGroupPage (新增)
  /chat/group/:groupId  → GroupInfoPage (新增)
  /chat/user/:userId    → UserProfilePage (新增)
```

ChatLayout 保持为外层布局（左侧 sidebar + 右侧主区域），右侧根据路由切换页面。

## 技术选型

- **路由库**: react-router-dom v6（嵌套路由 + Outlet）
- **UI**: 沿用现有 shadcn/ui + Tailwind CSS + lucide-react

## 页面详情

### 1. CreateGroupPage — 创建群

**路由**: `/chat/create-group`
**入口**: Sidebar 聊天 Tab 新增"创建群"按钮

**内容**:
- 群名称输入框（必填）
- 群头像 URL 输入框（可选）
- 好友列表复选框（多选，作为初始成员）
- 底部"创建"按钮

**交互**:
1. 用户填写群名、选择好友
2. 点击创建 → `im.group.create(groupName, 0, memberIds[])`
3. 成功后 `fetchConversations()` 刷新列表
4. 跳转到新群会话 `/chat`

**SDK 依赖**: `group.create()`
**Store 依赖**: `state.friends`

### 2. GroupInfoPage — 群详情/成员管理

**路由**: `/chat/group/:groupId`
**入口**: 点击群聊会话的聊天头部区域

**内容**:
- **群基本信息**: 群头像、群名、群 ID、群主、成员数
- **群成员列表**: 头像、昵称、角色标签、入群时间
- **角色标签**: 群主=200→`[群主]`(红), 管理员=100→`[管理员]`(蓝), 普通=1→无
- **操作按钮**:
  - 我是群主 → 解散群、成员项显示"踢出"和"设为管理员"
  - 我是管理员 → 成员项显示"踢出"（仅对普通成员）
  - 我是普通成员 → 退出群

**数据来源**:
- `im.group.info(groupId)` → GroupInfo
- `im.group.members(groupId)` → GroupMember[]

**SDK 依赖**: `group.info()`, `group.members()`, `group.kick()`, `group.disband()`, `group.quit()`
**Store 依赖**: `state.userId`

### 3. UserProfilePage — 用户个人信息

**路由**: `/chat/user/:userId`
**入口**: 点击聊天消息头像、好友列表项、群成员列表项

**内容**:
- 大尺寸头像、昵称、User ID、appMangerLevel
- 操作区（根据关系动态显示）:
  - 自己 → "编辑资料"按钮（调用 `user.update()`）
  - 好友 → "发消息"、"删除好友"、"拉黑"
  - 非好友 → "加好友"

**数据来源**: `im.user.info(userId)`

**SDK 依赖**: `user.info()`, `user.update()`, `friend.remove()`, `friend.black()`, `friend.apply()`
**Store 依赖**: `state.userId`, `state.friends`

### 4. ChatArea 增强

**路由**: `/chat`（现有组件增强）

**改动点**:
- **群聊发送**: conversationType=2 时调用 `im.message.sendGroup(groupId, contentType, content)`
- **消息撤回**: 自己的消息显示"撤回"按钮 → `im.message.revoke(messageId)`
- **历史加载**: 进入会话时加载最近 20 条:
  ```ts
  const maxSeq = await im.message.seq(convId);
  const msgs = await im.message.pull(convId, maxSeq - 20, maxSeq);
  ```
- **头部点击**: 群聊头部→`/chat/group/:groupId`，单聊头部→`/chat/user/:userId`

**SDK 依赖**: `message.sendGroup()`, `message.revoke()`, `message.pull()`, `message.seq()`
**Store 依赖**: 现有 conversation/message state

## Store 新增 action

```ts
// 新增 dispatch action
SET_GROUP_MEMBERS: { groupId: string; members: GroupMember[] }
SET_GROUP_INFO: { groupId: string; info: GroupInfo }
SET_USER_PROFILE: { userId: string; info: UserInfo }

// 新增 store method
fetchGroupMembers: (groupId: string) => void
fetchUserProfile: (userId: string) => void
```

## 文件清单

| 文件 | 操作 |
|------|------|
| `im-web/package.json` | 添加 `react-router-dom` 依赖 |
| `im-web/src/App.tsx` | 添加 Router + Routes 配置 |
| `im-web/src/pages/ChatLayout.tsx` | 添加 `<Outlet />` 替代 `<ChatArea />` |
| `im-web/src/pages/CreateGroupPage.tsx` | 新增 |
| `im-web/src/pages/GroupInfoPage.tsx` | 新增 |
| `im-web/src/pages/UserProfilePage.tsx` | 新增 |
| `im-web/src/components/Sidebar.tsx` | Chat Tab 新增"创建群"按钮 (路由跳转) |
| `im-web/src/components/ChatArea.tsx` | 群聊发送、撤回、历史加载、头部点击跳转 |
| `im-web/src/store/store.tsx` | 新增 state + action + method |

## 实现顺序

1. 安装 react-router-dom，改造 App.tsx 和 ChatLayout.tsx（路由骨架）
2. CreateGroupPage
3. GroupInfoPage
4. UserProfilePage
5. ChatArea 增强（群聊发送、撤回、历史加载）
6. Sidebar 入口按钮
