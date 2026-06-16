/** 业务操作名映射（对应后端 Operation 枚举的 opName） */
export const OP = {
  // User
  USER_REGISTER: "user.register",
  USER_ME: "user.me",
  USER_INFO: "user.info",
  USER_SEARCH: "user.search",
  USER_UPDATE: "user.update",
  // Friend
  FRIEND_APPLY: "friend.apply",
  FRIEND_APPROVE: "friend.approve",
  FRIEND_REMOVE: "friend.remove",
  FRIEND_LIST: "friend.list",
  FRIEND_BLACK: "friend.black",
  FRIEND_UNBLACK: "friend.unblack",
  FRIEND_BLACKLIST: "friend.blacklist",
  FRIEND_APPLY_SENT: "friend.get_sent_apply_list",
  FRIEND_APPLY_DETAIL: "friend.get_apply_detail",
  FRIEND_APPLY_UNHANDLED_COUNT: "friend.get_unhandled_apply_count",
  // Group
  GROUP_CREATE: "group.create",
  GROUP_JOIN: "group.join",
  GROUP_QUIT: "group.quit",
  GROUP_KICK: "group.kick",
  GROUP_DISBAND: "group.disband",
  GROUP_INFO_UPDATE: "group.info.update",
  GROUP_INFO: "group.info",
  GROUP_LIST: "group.list",
  GROUP_SEARCH: "group.search",
  GROUP_MEMBERS: "group.members",
  GROUP_MUTE_ALL: "group.mute_all",
  GROUP_APPLY_LIST: "group.apply.list",
  GROUP_APPLY_UNHANDLED_COUNT: "group.apply.unhandled.count",
  GROUP_APPLY_APPROVE: "group.apply.approve",
  // Conversation
  CONVERSATION_LIST: "conversation.list",
  CONVERSATION_SET: "conversation.set",
  CONVERSATION_READ: "conversation.read",
  // Message
  CHAT_PULL: "chat.pull",
  CHAT_SEQ: "chat.seq",
  CHAT_SYNC: "chat.sync",
  CHAT_SEARCH: "chat.search",
  CHAT_SEND: "chat.send",
  CHAT_SEND_GROUP: "chat.send.group",
  CHAT_REVOKE: "msg_revoke",
  // File
  FILE_UPLOAD: "file.upload",
  FILE_UPLOAD_SIGN: "file.upload.sign",
  FILE_UPLOAD_COMPLETE: "file.upload.complete",
  FILE_DOWNLOAD_SIGN: "file.download.sign",
  FILE_MULTIPART_INIT: "file.multipart.init",
  FILE_MULTIPART_PART_SIGN: "file.multipart.part.sign",
  FILE_MULTIPART_UPLOAD: "file.multipart.upload",
  FILE_MULTIPART_COMPLETE: "file.multipart.complete",
  FILE_MULTIPART_ABORT: "file.multipart.abort",
  // Auth
  LOGIN: "login",
  REGISTER: "register",
  HEARTBEAT: "heartbeat",
} as const;

export type OpValue = (typeof OP)[keyof typeof OP];

/** 后端推送的 op 类型 */
export const PUSH_OP = {
  MESSAGE: "message",
  FRIEND_APPLY: "friend.apply",
  GROUP_APPLY: "group.apply",
  SYSTEM_MESSAGE: "system.message",
  MESSAGE_REVOKED: "msg_revoke",
} as const;
