package com.im.api;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AuthzPolicyTest {

    @Test
    void everyOperationHasAnExplicitAuthorizationPolicy() {
        Set<Operation> policyOperations = AuthzPolicy.all().stream()
                .map(AuthzPolicy::operation)
                .collect(Collectors.toSet());

        assertEquals(EnumSet.allOf(Operation.class), policyOperations);
    }

    @Test
    void publicOperationsDoNotRequireAuthentication() {
        AuthzPolicy.all().stream()
                .filter(policy -> policy.scope() == AuthzPolicy.Scope.PUBLIC)
                .forEach(policy -> assertFalse(policy.operation().requireAuth(),
                        policy.operation() + " is public but Operation requires auth"));
    }

    @Test
    void authenticatedOperationsHaveResourceOwnershipRule() {
        AuthzPolicy.all().stream()
                .filter(policy -> policy.operation().requireAuth())
                .forEach(policy -> {
                    assertFalse(policy.scope().name().isBlank(), policy.operation() + " scope must be explicit");
                    assertFalse(policy.rule().isBlank(), policy.operation() + " rule must be documented");
                    assertFalse(policy.enforcedBy().isBlank(), policy.operation() + " enforcement owner must be documented");
                });
    }

    @Test
    void sensitiveOperationsAreNotOnlyAuthenticatedTheyAreResourceScoped() {
        assertEquals(AuthzPolicy.Scope.CONVERSATION_MEMBER, AuthzPolicy.forOperation(Operation.CHAT_REVOKE).scope());
        assertEquals(AuthzPolicy.Scope.GROUP_MANAGER, AuthzPolicy.forOperation(Operation.GROUP_APPLY_APPROVE).scope());
        assertEquals(AuthzPolicy.Scope.GROUP_OWNER, AuthzPolicy.forOperation(Operation.GROUP_DISBAND).scope());
        assertEquals(AuthzPolicy.Scope.ADMIN, AuthzPolicy.forOperation(Operation.ADMIN_SYSTEM_MESSAGE_PUBLISH).scope());
    }
}
