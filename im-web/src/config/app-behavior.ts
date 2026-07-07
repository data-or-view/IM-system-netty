export const APP_BEHAVIOR = {
  cache: {
    userProfileTtlMs: 5 * 60 * 1000,
    groupInfoTtlMs: 5 * 60 * 1000,
    groupMembersTtlMs: 60 * 1000,
  },
  refresh: {
    debounceMs: 80,
  },
  search: {
    defaultLimit: 20,
  },
  messages: {
    historyPageSize: 20,
  },
  systemMessages: {
    listLimit: 30,
  },
  errors: {
    toastDedupeMs: 3000,
  },
} as const;
