package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 消息实体，映射 {@code im_messages} 表。
 *
 * <p>对应 OpenIM {@code model.MsgDataModel + MsgInfoModel}。
 * 将撤回信息和删除标记内联到消息行，不另建表。</p>
 *
 * <p>SenderNickname/SenderFaceURL 冗余存储：即使以后用户改名改头像，
 * 历史消息里看到的是发消息那一刻的信息。</p>
 */
@TableName("im_messages")
public class MessageEntity {

    @TableField("id")
    private Long id;

    @TableField("client_msg_id")
    private String clientMsgId;

    @TableField("server_msg_id")
    private String serverMsgId;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("seq")
    private long seq;

    @TableField("send_id")
    private String sendId;

    @TableField("recv_id")
    private String recvId;

    @TableField("group_id")
    private String groupId;

    @TableField("sender_platform_id")
    private int senderPlatformId;

    @TableField("sender_nickname")
    private String senderNickname;

    @TableField("sender_face_url")
    private String senderFaceUrl;

    @TableField("session_type")
    private int sessionType;

    @TableField("msg_from")
    private int msgFrom;

    @TableField("content_type")
    private int contentType;

    @TableField("content")
    private String content;

    @TableField("status")
    private int status;

    @TableField("is_read")
    private int isRead;

    // ── 撤回信息 ──
    @TableField("revoke_user_id")
    private String revokeUserId;

    @TableField("revoke_role")
    private int revokeRole;

    @TableField("revoke_nickname")
    private String revokeNickname;

    @TableField("revoke_time")
    private long revokeTime;

    // ── 删除标记 ──
    @TableField("del_user_ids")
    private String delUserIds;

    // ── @用户 ──
    @TableField("at_user_ids")
    private String atUserIds;

    // ── 离线推送 ──
    @TableField("offline_title")
    private String offlineTitle;

    @TableField("offline_desc")
    private String offlineDesc;

    @TableField("offline_ex")
    private String offlineEx;

    @TableField("ios_push_sound")
    private String iosPushSound;

    @TableField("ios_badge_count")
    private int iosBadgeCount;

    // ── 扩展 ──
    @TableField("attached_info")
    private String attachedInfo;

    @TableField("ex")
    private String ex;

    @TableField("sent_at")
    private long sentAt;

    @TableField("created_at")
    private long createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getClientMsgId() { return clientMsgId; }
    public void setClientMsgId(String clientMsgId) { this.clientMsgId = clientMsgId; }

    public String getServerMsgId() { return serverMsgId; }
    public void setServerMsgId(String serverMsgId) { this.serverMsgId = serverMsgId; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }

    public String getSendId() { return sendId; }
    public void setSendId(String sendId) { this.sendId = sendId; }

    public String getRecvId() { return recvId; }
    public void setRecvId(String recvId) { this.recvId = recvId; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public int getSenderPlatformId() { return senderPlatformId; }
    public void setSenderPlatformId(int senderPlatformId) { this.senderPlatformId = senderPlatformId; }

    public String getSenderNickname() { return senderNickname; }
    public void setSenderNickname(String senderNickname) { this.senderNickname = senderNickname; }

    public String getSenderFaceUrl() { return senderFaceUrl; }
    public void setSenderFaceUrl(String senderFaceUrl) { this.senderFaceUrl = senderFaceUrl; }

    public int getSessionType() { return sessionType; }
    public void setSessionType(int sessionType) { this.sessionType = sessionType; }

    public int getMsgFrom() { return msgFrom; }
    public void setMsgFrom(int msgFrom) { this.msgFrom = msgFrom; }

    public int getContentType() { return contentType; }
    public void setContentType(int contentType) { this.contentType = contentType; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public int getIsRead() { return isRead; }
    public void setIsRead(int isRead) { this.isRead = isRead; }

    public String getRevokeUserId() { return revokeUserId; }
    public void setRevokeUserId(String revokeUserId) { this.revokeUserId = revokeUserId; }

    public int getRevokeRole() { return revokeRole; }
    public void setRevokeRole(int revokeRole) { this.revokeRole = revokeRole; }

    public String getRevokeNickname() { return revokeNickname; }
    public void setRevokeNickname(String revokeNickname) { this.revokeNickname = revokeNickname; }

    public long getRevokeTime() { return revokeTime; }
    public void setRevokeTime(long revokeTime) { this.revokeTime = revokeTime; }

    public String getDelUserIds() { return delUserIds; }
    public void setDelUserIds(String delUserIds) { this.delUserIds = delUserIds; }

    public String getAtUserIds() { return atUserIds; }
    public void setAtUserIds(String atUserIds) { this.atUserIds = atUserIds; }

    public String getOfflineTitle() { return offlineTitle; }
    public void setOfflineTitle(String offlineTitle) { this.offlineTitle = offlineTitle; }

    public String getOfflineDesc() { return offlineDesc; }
    public void setOfflineDesc(String offlineDesc) { this.offlineDesc = offlineDesc; }

    public String getOfflineEx() { return offlineEx; }
    public void setOfflineEx(String offlineEx) { this.offlineEx = offlineEx; }

    public String getIosPushSound() { return iosPushSound; }
    public void setIosPushSound(String iosPushSound) { this.iosPushSound = iosPushSound; }

    public int getIosBadgeCount() { return iosBadgeCount; }
    public void setIosBadgeCount(int iosBadgeCount) { this.iosBadgeCount = iosBadgeCount; }

    public String getAttachedInfo() { return attachedInfo; }
    public void setAttachedInfo(String attachedInfo) { this.attachedInfo = attachedInfo; }

    public String getEx() { return ex; }
    public void setEx(String ex) { this.ex = ex; }

    public long getSentAt() { return sentAt; }
    public void setSentAt(long sentAt) { this.sentAt = sentAt; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
