import {
  type SystemChannel,
  type SystemMessageInboxItem,
  type SystemMessageSummary,
  type SystemUnreadCount,
} from "../types.js";
import { type HttpAPI, requireHttp } from "./http-api.js";

export interface ListSystemMessagesParams {
  channelId?: string;
  onlyUnread?: boolean;
  limit?: number;
  cursor?: number;
}

export interface PublishSystemMessageParams {
  channelId: string;
  title: string;
  summary?: string;
  content: string;
  contentType?: string;
  priority?: number;
  expireAt?: number;
  targetUserIds: string[];
}

export class SystemAPI {
  constructor(private transport?: HttpAPI) {}

  channels(): Promise<SystemChannel[]> {
    return requireHttp(this.transport).get<{ channels: SystemChannel[] }>("/api/system/channels")
      .then((data) => data.channels);
  }

  messages(params: ListSystemMessagesParams = {}): Promise<SystemMessageInboxItem[]> {
    return requireHttp(this.transport).get<{ messages: SystemMessageInboxItem[] }>("/api/system/messages", { ...params })
      .then((data) => data.messages);
  }

  detail(messageId: string): Promise<SystemMessageInboxItem> {
    return requireHttp(this.transport).get<SystemMessageInboxItem>("/api/system/messages/detail", { messageId });
  }

  markRead(messageId: string): Promise<void> {
    return requireHttp(this.transport).post("/api/system/messages/read", { messageId }).then(() => undefined);
  }

  markAllRead(channelId?: string): Promise<{ updated: number }> {
    return requireHttp(this.transport).post<{ updated: number }>("/api/system/messages/read-all", { channelId });
  }

  unreadCount(channelId?: string): Promise<SystemUnreadCount> {
    return requireHttp(this.transport).get<SystemUnreadCount>("/api/system/messages/unread-count", { channelId });
  }

  publish(params: PublishSystemMessageParams): Promise<SystemMessageSummary> {
    return requireHttp(this.transport).post<{ message: SystemMessageSummary }>("/api/admin/system/messages/publish", { ...params })
      .then((data) => data.message);
  }
}
