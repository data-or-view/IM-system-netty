-- ============================================================
-- IM System - 数据库初始化脚本
-- 数据库: im_system, 字符集: utf8mb4
-- ============================================================

CREATE DATABASE IF NOT EXISTS im_system
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE im_system;

-- ── 用户表 ──
CREATE TABLE IF NOT EXISTS im_users (
    user_id      VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '用户ID',
    nickname     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '昵称',
    face_url     VARCHAR(512) NOT NULL DEFAULT '' COMMENT '头像URL',
    status       TINYINT      NOT NULL DEFAULT 0  COMMENT '状态: 0=离线, 1=在线',
    created_at   BIGINT       NOT NULL DEFAULT 0  COMMENT '创建时间(毫秒)',
    updated_at   BIGINT       NOT NULL DEFAULT 0  COMMENT '更新时间(毫秒)',
    INDEX idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ── 好友关系表 ──
CREATE TABLE IF NOT EXISTS im_friends (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id     VARCHAR(64)  NOT NULL COMMENT '用户ID',
    friend_id    VARCHAR(64)  NOT NULL COMMENT '好友用户ID',
    remark       VARCHAR(128) NOT NULL DEFAULT '' COMMENT '备注',
    created_at   BIGINT       NOT NULL DEFAULT 0,
    UNIQUE KEY uk_owner_friend (owner_id, friend_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='好友关系表';

-- ── 群组表 ──
CREATE TABLE IF NOT EXISTS im_groups (
    group_id     VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '群ID',
    group_name   VARCHAR(128) NOT NULL DEFAULT '' COMMENT '群名称',
    owner_id     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '群主ID',
    face_url     VARCHAR(512) NOT NULL DEFAULT '' COMMENT '群头像',
    notification TEXT         COMMENT '群公告',
    member_count INT          NOT NULL DEFAULT 0  COMMENT '成员数',
    created_at   BIGINT       NOT NULL DEFAULT 0,
    updated_at   BIGINT       NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群组表';

-- ── 群成员表 ──
CREATE TABLE IF NOT EXISTS im_group_members (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id     VARCHAR(64)  NOT NULL COMMENT '群ID',
    user_id      VARCHAR(64)  NOT NULL COMMENT '用户ID',
    role         VARCHAR(16)  NOT NULL DEFAULT 'member' COMMENT '角色: owner/admin/member',
    joined_at    BIGINT       NOT NULL DEFAULT 0,
    UNIQUE KEY uk_group_user (group_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群成员表';

-- ── 消息表 ──
CREATE TABLE IF NOT EXISTS im_messages (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '物理主键',
    message_id     VARCHAR(64)  NOT NULL COMMENT '消息ID（全局唯一）',
    conversation_id VARCHAR(128) NOT NULL COMMENT '会话ID',
    seq            BIGINT       NOT NULL COMMENT '会话内序号',
    sender_id      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '发送者ID',
    cmd            TINYINT      NOT NULL DEFAULT 0  COMMENT '命令类型',
    body           MEDIUMTEXT   COMMENT '消息体(JSON)',
    status         TINYINT      NOT NULL DEFAULT 0  COMMENT '状态',
    sent_at        BIGINT       NOT NULL DEFAULT 0  COMMENT '发送时间',
    created_at     BIGINT       NOT NULL DEFAULT 0,
    UNIQUE KEY uk_conversation_seq (conversation_id, seq),
    INDEX idx_message_id (message_id),
    INDEX idx_sender (sender_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息表';

-- ── 会话表 ──
CREATE TABLE IF NOT EXISTS im_conversations (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id  VARCHAR(128) NOT NULL COMMENT '会话ID',
    user_id          VARCHAR(64)  NOT NULL COMMENT '用户ID',
    session_type     TINYINT      NOT NULL DEFAULT 0 COMMENT '会话类型: 1=单聊, 2=群聊',
    unread_count     INT          NOT NULL DEFAULT 0,
    last_msg_content TEXT         COMMENT '最后消息预览',
    last_msg_id      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '最后消息ID',
    last_msg_seq     BIGINT       NOT NULL DEFAULT 0  COMMENT '最后消息序号',
    last_msg_time    BIGINT       NOT NULL DEFAULT 0  COMMENT '最后消息时间',
    is_pinned        TINYINT      NOT NULL DEFAULT 0  COMMENT '是否置顶',
    recv_msg_opt     TINYINT      NOT NULL DEFAULT 0  COMMENT '接收选项: 0=正常, 1=免打扰, 2=不接收',
    updated_at       BIGINT       NOT NULL DEFAULT 0,
    UNIQUE KEY uk_user_conversation (user_id, conversation_id),
    INDEX idx_last_msg_time (user_id, last_msg_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话表';
