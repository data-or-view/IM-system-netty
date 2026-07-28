-- Version 2 is identified by the SHA-256 checksum of this ordered resource.
-- Java inspects each target before executing its statement so MySQL DDL retries are safe.

-- migration-step: add-users-password-hash
ALTER TABLE im_users
    ADD COLUMN password_hash VARCHAR(255) NOT NULL DEFAULT '' AFTER global_recv_msg_opt;

-- migration-step: widen-messages-revoke-role
ALTER TABLE im_messages
    MODIFY COLUMN revoke_role SMALLINT NOT NULL DEFAULT 0 COMMENT '撤回者角色';

-- migration-step: drop-global-client-msg-unique
ALTER TABLE im_messages
    DROP INDEX uk_client_msg;

-- migration-step: add-conversation-client-msg-unique
ALTER TABLE im_messages
    ADD UNIQUE KEY uk_conversation_client_msg (conversation_id, client_msg_id);

-- migration-step: add-client-msg-lookup
ALTER TABLE im_messages
    ADD INDEX idx_client_msg (client_msg_id);

-- migration-step: create-conversation-projection-events
CREATE TABLE IF NOT EXISTS im_conversation_projection_events (
    owner_user_id   VARCHAR(64)  NOT NULL,
    conversation_id VARCHAR(128) NOT NULL,
    message_id      VARCHAR(128) NOT NULL,
    message_seq     BIGINT       NOT NULL,
    created_at      BIGINT       NOT NULL,
    PRIMARY KEY (owner_user_id, conversation_id, message_id),
    UNIQUE KEY uk_conversation_projection_message (owner_user_id, conversation_id, message_id),
    KEY idx_projection_unread (owner_user_id, conversation_id, message_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Inbound conversation projection events';

-- migration-step: create-schema-versions
CREATE TABLE IF NOT EXISTS im_schema_versions (
    version       INT          NOT NULL PRIMARY KEY,
    description   VARCHAR(255) NOT NULL,
    checksum      CHAR(64)     NOT NULL,
    installed_at  BIGINT       NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Managed IM schema versions';
