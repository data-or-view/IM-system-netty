package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 用户序号实体（用户视角的游标），映射 {@code im_seq_users} 表。
 *
 * <p>对应 OpenIM {@code model.SeqUser}。</p>
 *
 * <p>每个用户对每个会话维护自己的游标位置，用于：</p>
 * <ul>
 *   <li>已读位置标记（ReadSeq）</li>
 *   <li>未读计数 = MaxSeq - ReadSeq</li>
 *   <li>拉取历史边界（MinSeq ~ MaxSeq）</li>
 * </ul>
 *
 * <p>如果用户删除了历史消息，只后移 MinSeq，不删除实际消息。</p>
 */
@TableName("im_seq_users")
public class SeqUserEntity {

    @TableField("id")
    private Long id;

    @TableField("user_id")
    private String userId;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("min_seq")
    private long minSeq;

    @TableField("max_seq")
    private long maxSeq;

    @TableField("read_seq")
    private long readSeq;

    @TableField("updated_at")
    private long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public long getMinSeq() { return minSeq; }
    public void setMinSeq(long minSeq) { this.minSeq = minSeq; }

    public long getMaxSeq() { return maxSeq; }
    public void setMaxSeq(long maxSeq) { this.maxSeq = maxSeq; }

    public long getReadSeq() { return readSeq; }
    public void setReadSeq(long readSeq) { this.readSeq = readSeq; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
