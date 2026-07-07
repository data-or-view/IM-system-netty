import {
  GroupJoinVerification,
  GroupMemberRole,
  type GroupMemberRoleValue,
} from "im-sdk";

export function roleLabel(role: GroupMemberRoleValue): { text: string; className: string } | null {
  if (role === GroupMemberRole.OWNER) {
    return { text: "群主", className: "text-red-500 bg-red-50 border-red-200" };
  }
  if (role === GroupMemberRole.ADMIN) {
    return { text: "管理员", className: "text-blue-500 bg-blue-50 border-blue-200" };
  }
  return null;
}

export function roleText(role: GroupMemberRoleValue): string {
  if (role === GroupMemberRole.OWNER) return "群主";
  if (role === GroupMemberRole.ADMIN) return "管理员";
  return "成员";
}

export function joinPolicyText(policy?: string): string {
  if (policy === GroupJoinVerification.NEED_APPROVAL) return "需要审批";
  if (policy === GroupJoinVerification.INVITE_ONLY) return "仅邀请入群";
  if (policy === GroupJoinVerification.FORBIDDEN) return "禁止加入";
  return "可直接加入";
}
