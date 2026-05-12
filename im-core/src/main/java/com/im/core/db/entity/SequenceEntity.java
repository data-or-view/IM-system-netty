package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 会话序号实体（序号发生器），映射 {@code im_sequences} 表。
 *
 * <p>对应 OpenIM {@code model.SeqConversation}。</p>
 *
 * <p>每条消息需要一个会话内递增的 {@code seq}，用于排序、去重、分页。
 * 使用 MySQL 原子自增：</p>
 * <pre>
 * INSERT INTO im_sequences (conversation_id, max_seq, min_seq, updated_at)
 * VALUES ('conv_abc', 1, 0, NOW())
 * ON DUPLICATE KEY UPDATE max_seq = LAST_INSERT_ID(max_seq + 1);
 * // 然后 SELECT LAST_INSERT_ID() 获取新 seq
 * </pre>
 *
 * <p>MinSeq：消息归档或清理时后移，表示该会话可拉取的历史最小序号。</p>
 */
@TableName("im_sequences")
public class SequenceEntity {

    @TableField("conversation_id")
    private String conversationId;

    @TableField("max_seq")
    private long maxSeq;

    @TableField("min_seq")
    private long minSeq;

    @TableField("updated_at")
    private long updatedAt;

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public long getMaxSeq() { return maxSeq; }
    public void setMaxSeq(long maxSeq) { this.maxSeq = maxSeq; }

    public long getMinSeq() { return minSeq; }
    public void setMinSeq(long minSeq) { this.minSeq = minSeq; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
