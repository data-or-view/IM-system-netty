-- ============================================================
-- IM System - 数据库完整初始化脚本
-- 参考 OpenIM 数据模型设计（MongoDB → MySQL 映射）
-- 数据库: im_system, 字符集: utf8mb4
-- ============================================================

CREATE DATABASE IF NOT EXISTS im_system
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE im_system;

-- ============================================================
-- 1. 用户表
-- 对应 OpenIM: model.User
-- ============================================================
CREATE TABLE IF NOT EXISTS im_users (
    user_id              VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '用户ID',
    nickname             VARCHAR(128) NOT NULL DEFAULT '' COMMENT '昵称',
    face_url             VARCHAR(512) NOT NULL DEFAULT '' COMMENT '头像URL',
    ex                   TEXT                   COMMENT '扩展字段（JSON字符串，给开发者自定义）',
    app_manger_level     TINYINT      NOT NULL DEFAULT 0  COMMENT '管理员级别: 0=普通, 1=管理员, 2=超管',
    global_recv_msg_opt  TINYINT      NOT NULL DEFAULT 0  COMMENT '全局消息接收: 0=正常, 1=免打扰, 2=不接收',
    password_hash        VARCHAR(255) NOT NULL DEFAULT '' COMMENT '密码哈希（PBKDF2/可升级格式）',
    status               TINYINT      NOT NULL DEFAULT 1  COMMENT '状态: 0=禁用, 1=正常',
    created_at           BIGINT       NOT NULL DEFAULT 0  COMMENT '创建时间(毫秒)',
    updated_at           BIGINT       NOT NULL DEFAULT 0  COMMENT '更新时间(毫秒)',
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ============================================================
-- 2. 好友关系表
-- 对应 OpenIM: model.Friend
-- ============================================================
CREATE TABLE IF NOT EXISTS im_friends (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '物理主键',
    owner_user_id     VARCHAR(64)  NOT NULL COMMENT '用户ID（谁的好友列表）',
    friend_user_id    VARCHAR(64)  NOT NULL COMMENT '好友用户ID',
    remark            VARCHAR(128) NOT NULL DEFAULT '' COMMENT '好友备注',
    add_source        TINYINT      NOT NULL DEFAULT 0  COMMENT '添加来源: 1=搜索, 2=二维码, 3=群添加',
    operator_user_id  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '操作人ID',
    ex                TEXT         COMMENT '扩展字段',
    is_pinned         TINYINT      NOT NULL DEFAULT 0  COMMENT '是否在好友列表置顶: 0=否, 1=是',
    status            TINYINT      NOT NULL DEFAULT 1  COMMENT '状态: 0=删除, 1=正常',
    created_at        BIGINT       NOT NULL DEFAULT 0  COMMENT '成为好友时间',
    UNIQUE KEY uk_owner_friend (owner_user_id, friend_user_id),
    INDEX idx_friend_user (friend_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='好友关系表';

-- ============================================================
-- 3. 好友申请表
-- 对应 OpenIM: model.FriendRequest
-- ============================================================
CREATE TABLE IF NOT EXISTS im_friend_requests (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '物理主键',
    from_user_id      VARCHAR(64)  NOT NULL COMMENT '申请人ID',
    to_user_id        VARCHAR(64)  NOT NULL COMMENT '被申请人ID',
    handle_result     TINYINT      NOT NULL DEFAULT 0  COMMENT '处理结果: 0=待处理, 1=同意, 2=拒绝',
    req_msg           VARCHAR(512) NOT NULL DEFAULT '' COMMENT '申请附言',
    handler_user_id   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '处理人ID',
    handle_msg        VARCHAR(512) NOT NULL DEFAULT '' COMMENT '处理回复',
    handle_time       BIGINT       NOT NULL DEFAULT 0  COMMENT '处理时间(毫秒)',
    ex                TEXT         COMMENT '扩展字段',
    created_at        BIGINT       NOT NULL DEFAULT 0  COMMENT '申请时间',
    UNIQUE KEY uk_from_to (from_user_id, to_user_id),
    INDEX idx_to_user (to_user_id, handle_result, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='好友申请表';

-- ============================================================
-- 4. 黑名单表
-- 对应 OpenIM: model.Black
-- ============================================================
CREATE TABLE IF NOT EXISTS im_blacklist (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '物理主键',
    owner_user_id     VARCHAR(64)  NOT NULL COMMENT '拉黑者用户ID',
    block_user_id     VARCHAR(64)  NOT NULL COMMENT '被拉黑用户ID',
    add_source        TINYINT      NOT NULL DEFAULT 0  COMMENT '来源',
    operator_user_id  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '操作人ID',
    ex                TEXT         COMMENT '扩展字段',
    created_at        BIGINT       NOT NULL DEFAULT 0  COMMENT '拉黑时间',
    UNIQUE KEY uk_owner_block (owner_user_id, block_user_id),
    INDEX idx_block_user (block_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='黑名单表';

-- ============================================================
-- 5. 群组表
-- 对应 OpenIM: model.Group
-- ============================================================
CREATE TABLE IF NOT EXISTS im_groups (
    group_id             VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '群组ID',
    group_name           VARCHAR(128) NOT NULL DEFAULT '' COMMENT '群名称',
    notification         TEXT         COMMENT '群公告',
    introduction         VARCHAR(512) NOT NULL DEFAULT '' COMMENT '群简介',
    face_url             VARCHAR(512) NOT NULL DEFAULT '' COMMENT '群头像URL',
    owner_user_id        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '群主ID',
    member_count         INT          NOT NULL DEFAULT 0  COMMENT '成员数',
    status               TINYINT      NOT NULL DEFAULT 0  COMMENT '状态: 0=正常, 1=封禁, 2=解散',
    group_type           TINYINT      NOT NULL DEFAULT 0  COMMENT '群类型: 0=私有群, 1=公开群',
    need_verification    TINYINT      NOT NULL DEFAULT 0  COMMENT '加群验证: 0=无条件, 1=需验证, 2=需邀请, 3=不允许',
    look_member_info     TINYINT      NOT NULL DEFAULT 0  COMMENT '成员信息可见: 0=所有人可见, 1=仅管理员',
    apply_member_friend  TINYINT      NOT NULL DEFAULT 0  COMMENT '允许互加好友: 0=允许, 1=不允许',
    notification_user_id VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '最后更新公告的用户ID',
    notification_time    BIGINT       NOT NULL DEFAULT 0  COMMENT '公告更新时间',
    ex                   TEXT         COMMENT '扩展字段',
    created_at           BIGINT       NOT NULL DEFAULT 0  COMMENT '创建时间',
    updated_at           BIGINT       NOT NULL DEFAULT 0  COMMENT '更新时间',
    INDEX idx_owner (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群组表';

-- ============================================================
-- 6. 群成员表
-- 对应 OpenIM: model.GroupMember
-- ============================================================
CREATE TABLE IF NOT EXISTS im_group_members (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '物理主键',
    group_id          VARCHAR(64)  NOT NULL COMMENT '群ID',
    user_id           VARCHAR(64)  NOT NULL COMMENT '用户ID',
    nickname          VARCHAR(128) NOT NULL DEFAULT '' COMMENT '用户在群里的昵称（冗余）',
    face_url          VARCHAR(512) NOT NULL DEFAULT '' COMMENT '用户在群里的头像（冗余）',
    role_level        INT          NOT NULL DEFAULT 0  COMMENT '角色: 0=普通, 100=管理员, 200=群主',
    join_source       TINYINT      NOT NULL DEFAULT 0  COMMENT '入群来源',
    inviter_user_id   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '邀请人ID',
    operator_user_id  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '操作人ID',
    mute_end_time     BIGINT       NOT NULL DEFAULT 0  COMMENT '禁言截止时间(毫秒), 0=不禁言',
    ex                TEXT         COMMENT '扩展字段',
    joined_at         BIGINT       NOT NULL DEFAULT 0  COMMENT '入群时间',
    UNIQUE KEY uk_group_user (group_id, user_id),
    INDEX idx_user_group (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群成员表';

-- ============================================================
-- 7. 加群申请表
-- 对应 OpenIM: model.GroupRequest
-- ============================================================
CREATE TABLE IF NOT EXISTS im_group_requests (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '物理主键',
    user_id           VARCHAR(64)  NOT NULL COMMENT '申请人ID',
    group_id          VARCHAR(64)  NOT NULL COMMENT '目标群ID',
    handle_result     TINYINT      NOT NULL DEFAULT 0  COMMENT '处理结果: 0=待处理, 1=同意, 2=拒绝',
    req_msg           VARCHAR(512) NOT NULL DEFAULT '' COMMENT '申请理由',
    handled_msg       VARCHAR(512) NOT NULL DEFAULT '' COMMENT '处理回复',
    handler_user_id   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '处理人ID',
    handled_time      BIGINT       NOT NULL DEFAULT 0  COMMENT '处理时间',
    join_source       TINYINT      NOT NULL DEFAULT 0  COMMENT '来源: 搜索/二维码/邀请',
    inviter_user_id   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '邀请人ID（如果是被邀请的）',
    ex                TEXT         COMMENT '扩展字段',
    created_at        BIGINT       NOT NULL DEFAULT 0  COMMENT '申请时间',
    INDEX idx_group (group_id, handle_result, created_at),
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='加群申请表';

-- ============================================================
-- 8. 会话表（每个用户有一份视图）
-- 对应 OpenIM: model.Conversation
--
-- 核心设计思想：
--   「会话是用户视图」——不是两个人共享一个会话记录，
--   而是每个用户独立拥有一份会话数据。
--   这样置顶、免打扰、删除等操作只影响自己。
-- ============================================================
CREATE TABLE IF NOT EXISTS im_conversations (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '物理主键',
    owner_user_id       VARCHAR(64)  NOT NULL COMMENT '会话所属用户ID',
    conversation_id     VARCHAR(128) NOT NULL COMMENT '会话ID（单聊: s_user1_user2, 群聊: g_groupId）',
    conversation_type   TINYINT      NOT NULL DEFAULT 0  COMMENT '会话类型: 1=单聊, 2=群聊',
    user_id             VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '对方用户ID（单聊时使用）',
    group_id            VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '群组ID（群聊时使用）',
    recv_msg_opt        TINYINT      NOT NULL DEFAULT 0  COMMENT '接收选项: 0=正常, 1=免打扰, 2=不接收',
    is_pinned           TINYINT      NOT NULL DEFAULT 0  COMMENT '是否置顶: 0=否, 1=是',
    is_private_chat     TINYINT      NOT NULL DEFAULT 0  COMMENT '是否私聊（仅互相可见）',
    burn_duration       INT          NOT NULL DEFAULT 0  COMMENT '阅后即焚时长(秒), 0=不开启',
    group_at_type       TINYINT      NOT NULL DEFAULT 0  COMMENT '@类型: 0=未@, 1=@我, 2=@所有人',
    attached_info       TEXT         COMMENT '附加信息(JSON)',
    ex                  TEXT         COMMENT '扩展字段',
    max_seq             BIGINT       NOT NULL DEFAULT 0  COMMENT '该会话已接收最大序号',
    min_seq             BIGINT       NOT NULL DEFAULT 0  COMMENT '该会话最小可用序号',
    unread_count        INT          NOT NULL DEFAULT 0  COMMENT '未读数',
    is_msg_destruct     TINYINT      NOT NULL DEFAULT 0  COMMENT '是否开启消息自毁: 0=否, 1=是',
    msg_destruct_time   INT          NOT NULL DEFAULT 0  COMMENT '自毁时间(秒)',
    created_at          BIGINT       NOT NULL DEFAULT 0  COMMENT '创建时间',
    updated_at          BIGINT       NOT NULL DEFAULT 0  COMMENT '更新时间',
    UNIQUE KEY uk_owner_conversation (owner_user_id, conversation_id),
    INDEX idx_owner_updated (owner_user_id, updated_at DESC),
    INDEX idx_conversation (conversation_id),
    INDEX idx_user (user_id),
    INDEX idx_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话表（用户视图）';

-- ============================================================
-- 9. 消息表
-- 对应 OpenIM: model.MsgDataModel（含消息体 + 撤回 + 删除记录）
--
-- 设计要点：
--   · SenderNickname/SenderFaceURL 冗余存储，避免用户改名后历史消息被影响
--   · revoke 相关字段内联到消息行（不另建表）
--   · content 存原始消息体JSON（TextContent/ImageContent/FileContent 的序列化）
-- ============================================================
CREATE TABLE IF NOT EXISTS im_messages (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '物理主键',
    client_msg_id       VARCHAR(64)  NOT NULL COMMENT '客户端消息ID（客户端生成，用于去重）',
    server_msg_id       VARCHAR(64)  NOT NULL COMMENT '服务端消息ID（唯一标识）',
    conversation_id     VARCHAR(128) NOT NULL COMMENT '会话ID',
    seq                 BIGINT       NOT NULL COMMENT '会话内全局递增序号',
    send_id             VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '发送者ID',
    recv_id             VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '接收者ID（单聊时）',
    group_id            VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '群组ID（群聊时）',
    sender_platform_id  TINYINT      NOT NULL DEFAULT 0  COMMENT '发送端平台: 1=iOS, 2=Android, 3=Win, 4=Mac, 5=Web',
    sender_nickname     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '发送者昵称（冗余，不可变）',
    sender_face_url     VARCHAR(512) NOT NULL DEFAULT '' COMMENT '发送者头像（冗余，不可变）',
    session_type        TINYINT      NOT NULL DEFAULT 0  COMMENT '会话类型: 1=单聊, 2=群聊',
    msg_from            TINYINT      NOT NULL DEFAULT 0  COMMENT '消息来源: 0=用户, 1=系统',
    content_type        INT          NOT NULL DEFAULT 0  COMMENT '消息内容类型（101=文本, 102=图片, 103=文件, ...）',
    content             MEDIUMTEXT   COMMENT '消息体(JSON)',
    status              TINYINT      NOT NULL DEFAULT 0  COMMENT '状态: 0=正常, 1=已撤回, 2=已删除',
    is_read             TINYINT      NOT NULL DEFAULT 0  COMMENT '是否已读: 0=未读, 1=已读',
    -- 撤回信息
    revoke_user_id      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '撤回者ID（为空表示未撤回）',
    revoke_role         TINYINT      NOT NULL DEFAULT 0  COMMENT '撤回者角色',
    revoke_nickname     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '撤回者昵称',
    revoke_time         BIGINT       NOT NULL DEFAULT 0  COMMENT '撤回时间',
    -- 删除记录（逗号分隔的用户ID列表）
    del_user_ids        TEXT         COMMENT '已删除此消息的用户ID列表(逗号分隔)',
    -- @用户
    at_user_ids         TEXT         COMMENT '@用户ID列表(逗号分隔)',
    -- 离线推送
    offline_title       VARCHAR(256) NOT NULL DEFAULT '' COMMENT '离线推送标题',
    offline_desc        TEXT         COMMENT '离线推送描述',
    offline_ex          TEXT         COMMENT '离线推送扩展',
    ios_push_sound      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT 'iOS推送音效',
    ios_badge_count     TINYINT      NOT NULL DEFAULT 0  COMMENT '是否更新iOS角标',
    -- 扩展
    attached_info       TEXT         COMMENT '附加信息',
    ex                  TEXT         COMMENT '扩展字段',
    sent_at             BIGINT       NOT NULL DEFAULT 0  COMMENT '发送时间(毫秒)',
    created_at          BIGINT       NOT NULL DEFAULT 0  COMMENT '创建时间(毫秒)',
    UNIQUE KEY uk_conversation_seq (conversation_id, seq),
    UNIQUE KEY uk_client_msg (client_msg_id),
    INDEX idx_server_msg (server_msg_id),
    INDEX idx_send_id (send_id),
    INDEX idx_recv_id (recv_id),
    INDEX idx_group (group_id),
    INDEX idx_content_type (conversation_id, content_type),
    INDEX idx_send_time (conversation_id, sent_at DESC),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息表';

-- ============================================================
-- 10. 会话序号表（序号发生器）
-- 对应 OpenIM: model.SeqConversation
--
-- 每条消息需要一个会话内递增 seq，用于排序/去重/分页。
-- 用 MySQL 的原子自增：UPDATE im_sequences SET max_seq = max_seq + 1
-- ============================================================
CREATE TABLE IF NOT EXISTS im_sequences (
    conversation_id  VARCHAR(128) NOT NULL PRIMARY KEY COMMENT '会话ID',
    max_seq          BIGINT       NOT NULL DEFAULT 0  COMMENT '当前最大序号',
    min_seq          BIGINT       NOT NULL DEFAULT 0  COMMENT '最小可用序号',
    updated_at       BIGINT       NOT NULL DEFAULT 0  COMMENT '更新时间',
    INDEX idx_min_seq (min_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话序号表';

-- ============================================================
-- 11. 用户序号表（用户视角的游标）
-- 对应 OpenIM: model.SeqUser
--
-- 每个用户对每个会话有自己的游标位置。
-- 用于：已读位置标记、未读计数、拉取历史边界。
-- ============================================================
CREATE TABLE IF NOT EXISTS im_seq_users (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '物理主键',
    user_id           VARCHAR(64)  NOT NULL COMMENT '用户ID',
    conversation_id   VARCHAR(128) NOT NULL COMMENT '会话ID',
    min_seq           BIGINT       NOT NULL DEFAULT 0  COMMENT '该用户在此会话可拉取的最小序号',
    max_seq           BIGINT       NOT NULL DEFAULT 0  COMMENT '该用户在此会话可拉取的最大序号',
    read_seq          BIGINT       NOT NULL DEFAULT 0  COMMENT '该用户已读到的序号位置',
    updated_at        BIGINT       NOT NULL DEFAULT 0  COMMENT '更新时间',
    UNIQUE KEY uk_user_conversation (user_id, conversation_id),
    INDEX idx_conversation (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户序号表';

-- ============================================================
-- 12. 文件对象表（上传文件元数据）
-- 对应 OpenIM: model.Object
-- ============================================================
CREATE TABLE IF NOT EXISTS im_objects (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '物理主键',
    name              VARCHAR(512) NOT NULL COMMENT '对象名（images/userId/uuid.jpg）',
    user_id           VARCHAR(64)  NOT NULL COMMENT '上传用户ID',
    hash              VARCHAR(128) NOT NULL DEFAULT '' COMMENT '文件SHA256（用于去重）',
    engine            VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '存储后端: minio/oss/cos/kodo/aws',
    object_key        VARCHAR(512) NOT NULL DEFAULT '' COMMENT '在存储后端的实际Key',
    file_size         BIGINT       NOT NULL DEFAULT 0  COMMENT '文件大小(字节)',
    content_type      VARCHAR(128) NOT NULL DEFAULT '' COMMENT 'MIME类型',
    file_group        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '分组: avatar/image/file/voice',
    ex                TEXT         COMMENT '扩展字段',
    created_at        BIGINT       NOT NULL DEFAULT 0  COMMENT '创建时间',
    UNIQUE KEY uk_name (name),
    INDEX idx_user (user_id),
    INDEX idx_hash (hash),
    INDEX idx_group (file_group),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件对象表';

-- ============================================================
-- 13. 消息状态机说明
-- ============================================================
-- im_messages.status:
--   0 = 正常
--   1 = 已撤回（revoke_user_id 非空）
--   2 = 已删除（当前用户视角，对应 del_user_ids）
--
-- im_sequences 用法：
--   INSERT INTO im_sequences (conversation_id, max_seq, min_seq, updated_at)
--   VALUES ('conv_abc', 0, 0, UNIX_TIMESTAMP())
--   ON DUPLICATE KEY UPDATE max_seq = max_seq + 1;
--   然后 SELECT max_seq 获取新 seq
-- ============================================================

-- ============================================================
-- v1.1 → v2.0 迁移要点（手工对照）
-- ============================================================
-- 旧表 im_users          → 已扩展：新增 ex, app_manger_level, global_recv_msg_opt
-- 旧表 im_friends        → 已更名：owner_id→owner_user_id, friend_id→friend_user_id
--                        → 新增：add_source, operator_user_id, ex, is_pinned
-- 旧表 im_groups         → 已扩展：新增 introduction, group_type, need_verification,
--                          look_member_info, apply_member_friend, notification_user_id,
--                          notification_time, ex
-- 旧表 im_group_members  → 已扩展：新增 nickname, face_url, role_level→int, join_source,
--                          inviter_user_id, operator_user_id, mute_end_time, ex
-- 旧表 im_conversations  → 已重构：owner_user_id + conversation_id 联合主键
--                        → 新增：conversation_type, user_id, group_id, is_private_chat,
--                          burn_duration, group_at_type, attached_info, ex, max_seq, min_seq,
--                          is_msg_destruct, msg_destruct_time
-- 旧表 im_messages       → 已重写：使用 OpenIM 字段命名方式
--                        → 新增：client_msg_id, server_msg_id, recv_id, group_id,
--                          sender_platform_id, sender_nickname, sender_face_url, session_type,
--                          msg_from, is_read, revoke_* 系列, del_user_ids, at_user_ids,
--                          offline_* 系列, ios_* 系列, attached_info, ex
-- 新增 im_friend_requests → 好友申请表
-- 新增 im_blacklist       → 黑名单表
-- 新增 im_group_requests  → 加群申请表
-- 新增 im_sequences       → 会话序号发生器
-- 新增 im_seq_users       → 用户级序号
-- 新增 im_objects         → 文件上传元数据
-- ============================================================
