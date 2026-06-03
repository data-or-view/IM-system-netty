package com.im.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ApiBoundaryTest {

    @Test
    void sessionApiDoesNotExposeNettyTypes() {
        assertNoNettyTypes(ISessionManager.class);
        assertNoNettyTypes(IConnectionSession.class);
        assertNoNettyTypes(ConnectionRef.class);
    }

    private static void assertNoNettyTypes(Class<?> type) {
        for (Method method : type.getMethods()) {
            assertFalse(isNettyType(method.getReturnType()), method + " returns a Netty type");
            Arrays.stream(method.getParameterTypes()).forEach(param ->
                    assertFalse(isNettyType(param), method + " accepts a Netty type"));
        }
    }

    private static boolean isNettyType(Class<?> type) {
        Package pkg = type.getPackage();
        return pkg != null && pkg.getName().startsWith("io.netty");
    }
}
