package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 会话实体（用户视图），映射 {@code im_conversations} 表。
 *
 * <p>对应 OpenIM {@code model.Conversation}。</p>
 *
 * <p>核心设计：每个用户独立拥有一份会话视图。</p>
 * <pre>
 * 单聊场景：A 和 B 聊天
 *   → A 有一条 conversation，ownerUserId=A, conversationID=s_AB
 *   → B 有一条 conversation，ownerUserId=B, conversationID=s_AB
 * A 置顶只影响自己的记录，B 不受影响。
 * </pre>
 */
@TableName("im_conversations")
public class ConversationEntity {

    @TableField("id")
    private Long id;

    @TableField("owner_user_id")
    private String ownerUserId;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("conversation_type")
    private int conversationType;

    @TableField("user_id")
    private String userId;

    @TableField("group_id")
    private String groupId;

    @TableField("recv_msg_opt")
    private int recvMsgOpt;

    @TableField("is_pinned")
    private int isPinned;

    @TableField("is_private_chat")
    private int isPrivateChat;

    @TableField("burn_duration")
    private int burnDuration;

    @TableField("group_at_type")
    private int groupAtType;

    @TableField("attached_info")
    private String attachedInfo;

    @TableField("ex")
    private String ex;

    @TableField("max_seq")
    private long maxSeq;

    @TableField("min_seq")
    private long minSeq;

    @TableField("is_msg_destruct")
    private int isMsgDestruct;

    @TableField("msg_destruct_time")
    private int msgDestructTime;

    @TableField("created_at")
    private long createdAt;

    @TableField("updated_at")
    private long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public int getConversationType() { return conversationType; }
    public void setConversationType(int conversationType) { this.conversationType = conversationType; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public int getRecvMsgOpt() { return recvMsgOpt; }
    public void setRecvMsgOpt(int recvMsgOpt) { this.recvMsgOpt = recvMsgOpt; }

    public int getIsPinned() { return isPinned; }
    public void setIsPinned(int isPinned) { this.isPinned = isPinned; }

    public int getIsPrivateChat() { return isPrivateChat; }
    public void setIsPrivateChat(int isPrivateChat) { this.isPrivateChat = isPrivateChat; }

    public int getBurnDuration() { return burnDuration; }
    public void setBurnDuration(int burnDuration) { this.burnDuration = burnDuration; }

    public int getGroupAtType() { return groupAtType; }
    public void setGroupAtType(int groupAtType) { this.groupAtType = groupAtType; }

    public String getAttachedInfo() { return attachedInfo; }
    public void setAttachedInfo(String attachedInfo) { this.attachedInfo = attachedInfo; }

    public String getEx() { return ex; }
    public void setEx(String ex) { this.ex = ex; }

    public long getMaxSeq() { return maxSeq; }
    public void setMaxSeq(long maxSeq) { this.maxSeq = maxSeq; }

    public long getMinSeq() { return minSeq; }
    public void setMinSeq(long minSeq) { this.minSeq = minSeq; }

    public int getIsMsgDestruct() { return isMsgDestruct; }
    public void setIsMsgDestruct(int isMsgDestruct) { this.isMsgDestruct = isMsgDestruct; }

    public int getMsgDestructTime() { return msgDestructTime; }
    public void setMsgDestructTime(int msgDestructTime) { this.msgDestructTime = msgDestructTime; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
