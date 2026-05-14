package com.im.api;

import java.util.Collections;
import java.util.List;

/**
 * 消息搜索结果。
 *
 * <p>包含匹配的消息列表和匹配总数，支持分页。</p>
 */
public class SearchMessagesResult {

    private final List<IMCommand> messages;
    private final int totalCount;
    private final boolean hasMore;

    public SearchMessagesResult(List<IMCommand> messages, int totalCount, boolean hasMore) {
        this.messages = messages != null ? messages : Collections.emptyList();
        this.totalCount = totalCount;
        this.hasMore = hasMore;
    }

    /** 当前页匹配的消息列表，按时间倒序排列。 */
    public List<IMCommand> getMessages() { return messages; }

    /** 匹配的消息总数（用于分页展示）。 */
    public int getTotalCount() { return totalCount; }

    /** 是否还有下一页。 */
    public boolean hasMore() { return hasMore; }

    /** 空结果。 */
    public static SearchMessagesResult empty() {
        return new SearchMessagesResult(Collections.emptyList(), 0, false);
    }
}
