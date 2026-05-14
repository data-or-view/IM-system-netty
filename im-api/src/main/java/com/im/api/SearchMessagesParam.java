package com.im.api;

import java.util.List;

/**
 * 消息搜索参数。
 *
 * <p>封装搜索消息的全部条件，所有字段均为可选，
 * 未设置的字段（null/-1）表示不按该条件过滤。</p>
 *
 * <p>使用示例：</p>
 * <pre>
 *   SearchMessagesParam param = SearchMessagesParam.builder()
 *       .userId("currentUserId")
 *       .keyword("Hello")
 *       .startTime(1700000000000L)
 *       .endTime(1700003600000L)
 *       .conversationIds(List.of("sg_abc", "sg_def"))
 *       .limit(20)
 *       .build();
 * </pre>
 */
public class SearchMessagesParam {

    /** 搜索发起者（用于权限过滤：只能搜自己参与的消息）。 */
    private final String userId;

    /** 搜索关键词（按消息内容模糊匹配）。 */
    private final String keyword;

    /** 消息类型过滤（null=全部，如 "text" / "image" / "file"）。 */
    private final List<String> contentTypeFilter;

    /** 限定会话范围（null=搜索全部会话，空列表=不返回结果）。 */
    private final List<String> conversationIds;

    /** 起始时间（毫秒时间戳，含），null=不限制。 */
    private final Long startTime;

    /** 结束时间（毫秒时间戳，含），null=不限制。 */
    private final Long endTime;

    /** 发送者 ID 过滤（null=全部发送者）。 */
    private final String senderId;

    /** 每页条数（1~100，默认 20）。 */
    private final int limit;

    /** 偏移量（分页用，从第几条开始，默认 0）。 */
    private final int offset;

    private SearchMessagesParam(String userId, String keyword, List<String> contentTypeFilter,
                                List<String> conversationIds, Long startTime, Long endTime,
                                String senderId, int limit, int offset) {
        this.userId = userId;
        this.keyword = keyword;
        this.contentTypeFilter = contentTypeFilter;
        this.conversationIds = conversationIds;
        this.startTime = startTime;
        this.endTime = endTime;
        this.senderId = senderId;
        this.limit = Math.min(Math.max(limit, 1), 100);
        this.offset = Math.max(offset, 0);
    }

    // ── getters ──

    public String getUserId() { return userId; }
    public String getKeyword() { return keyword; }
    public List<String> getContentTypeFilter() { return contentTypeFilter; }
    public List<String> getConversationIds() { return conversationIds; }
    public Long getStartTime() { return startTime; }
    public Long getEndTime() { return endTime; }
    public String getSenderId() { return senderId; }
    public int getLimit() { return limit; }
    public int getOffset() { return offset; }

    // ── builder ──

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String userId;
        private String keyword;
        private List<String> contentTypeFilter;
        private List<String> conversationIds;
        private Long startTime;
        private Long endTime;
        private String senderId;
        private int limit = 20;
        private int offset;

        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder keyword(String keyword) { this.keyword = keyword; return this; }
        public Builder contentTypeFilter(List<String> contentTypeFilter) { this.contentTypeFilter = contentTypeFilter; return this; }
        public Builder conversationIds(List<String> conversationIds) { this.conversationIds = conversationIds; return this; }
        public Builder startTime(Long startTime) { this.startTime = startTime; return this; }
        public Builder endTime(Long endTime) { this.endTime = endTime; return this; }
        public Builder senderId(String senderId) { this.senderId = senderId; return this; }
        public Builder limit(int limit) { this.limit = limit; return this; }
        public Builder offset(int offset) { this.offset = offset; return this; }

        public SearchMessagesParam build() {
            return new SearchMessagesParam(userId, keyword, contentTypeFilter,
                    conversationIds, startTime, endTime, senderId, limit, offset);
        }
    }
}
