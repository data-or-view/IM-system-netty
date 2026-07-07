package com.im.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiBoundaryTest {

    private static final List<String> FORBIDDEN_API_TYPE_PREFIXES = List.of(
            "io.netty.",
            "io.lettuce.",
            "org.apache.rocketmq.",
            "java.sql.",
            "com.baomidou.",
            "com.zaxxer.",
            "com.im.core.",
            "com.im.bootstrap.",
            "com.im.infrastructure.");

    @Test
    void sessionApiDoesNotExposeNettyTypes() {
        assertNoNettyTypes(ISessionManager.class);
        assertNoNettyTypes(IConnectionSession.class);
        assertNoNettyTypes(ConnectionRef.class);
    }

    @Test
    void publicApiSignaturesDoNotExposeInfrastructureTypes() throws Exception {
        for (Class<?> type : apiClasses()) {
            assertApiType(type, type.getName() + " class");
            assertApiType(type.getGenericSuperclass(), type.getName() + " superclass");
            Arrays.stream(type.getGenericInterfaces()).forEach(genericInterface ->
                    assertApiType(genericInterface, type.getName() + " implements forbidden API type"));
            Arrays.stream(type.getTypeParameters()).forEach(typeParameter ->
                    assertApiType(typeParameter, type.getName() + " type parameter exposes forbidden API type"));
            for (Method method : type.getMethods()) {
                assertApiType(method.getGenericReturnType(), method + " returns forbidden API type");
                Arrays.stream(method.getGenericParameterTypes()).forEach(param ->
                        assertApiType(param, method + " accepts forbidden API type"));
                Arrays.stream(method.getGenericExceptionTypes()).forEach(exception ->
                        assertApiType(exception, method + " throws forbidden API type"));
                Arrays.stream(method.getTypeParameters()).forEach(typeParameter ->
                        assertApiType(typeParameter, method + " type parameter exposes forbidden API type"));
            }
            for (Constructor<?> constructor : type.getConstructors()) {
                Arrays.stream(constructor.getGenericParameterTypes()).forEach(param ->
                        assertApiType(param, constructor + " accepts forbidden API type"));
                Arrays.stream(constructor.getGenericExceptionTypes()).forEach(exception ->
                        assertApiType(exception, constructor + " throws forbidden API type"));
                Arrays.stream(constructor.getTypeParameters()).forEach(typeParameter ->
                        assertApiType(typeParameter, constructor + " type parameter exposes forbidden API type"));
            }
            for (Field field : type.getFields()) {
                assertApiType(field.getGenericType(), field + " exposes forbidden API type");
            }
        }
    }

    @Test
    void genericSignatureScannerRejectsForbiddenNestedTypes() throws Exception {
        Method method = GenericLeakFixture.class.getMethod("leakedConnections");

        assertTrue(containsForbiddenApiType(method.getGenericReturnType()));
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

    private static void assertApiType(Type type, String message) {
        assertFalse(containsForbiddenApiType(type), message);
    }

    private static boolean containsForbiddenApiType(Type type) {
        return containsForbiddenApiType(type, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean containsForbiddenApiType(Type type, Set<Type> visited) {
        if (type == null || !visited.add(type)) {
            return false;
        }
        if (type instanceof Class<?> clazz) {
            return isForbiddenClass(clazz);
        }
        if (type instanceof ParameterizedType parameterizedType) {
            return containsForbiddenApiType(parameterizedType.getOwnerType(), visited) ||
                    containsForbiddenApiType(parameterizedType.getRawType(), visited) ||
                    Arrays.stream(parameterizedType.getActualTypeArguments())
                            .anyMatch(argument -> containsForbiddenApiType(argument, visited));
        }
        if (type instanceof GenericArrayType genericArrayType) {
            return containsForbiddenApiType(genericArrayType.getGenericComponentType(), visited);
        }
        if (type instanceof TypeVariable<?> typeVariable) {
            return Arrays.stream(typeVariable.getBounds())
                    .anyMatch(bound -> containsForbiddenApiType(bound, visited));
        }
        if (type instanceof WildcardType wildcardType) {
            return Arrays.stream(wildcardType.getLowerBounds())
                    .anyMatch(bound -> containsForbiddenApiType(bound, visited)) ||
                    Arrays.stream(wildcardType.getUpperBounds())
                            .anyMatch(bound -> containsForbiddenApiType(bound, visited));
        }
        return false;
    }

    private static boolean isForbiddenClass(Class<?> type) {
        Class<?> actual = unwrapArray(type);
        Package pkg = actual.getPackage();
        String packageName = pkg != null ? pkg.getName() + "." : "";
        return FORBIDDEN_API_TYPE_PREFIXES.stream().anyMatch(packageName::startsWith);
    }

    private static Class<?> unwrapArray(Class<?> type) {
        Class<?> actual = type;
        while (actual.isArray()) {
            actual = actual.getComponentType();
        }
        return actual;
    }

    private static List<Class<?>> apiClasses() throws Exception {
        Path classesRoot = productionClassesRoot();
        Path apiRoot = classesRoot.resolve("com/im/api");
        assertTrue(Files.isDirectory(apiRoot), "API production classes not found: " + apiRoot);
        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(apiRoot)) {
            paths
                    .filter(path -> path.toString().endsWith(".class"))
                    .map(classesRoot::relativize)
                    .map(path -> path.toString()
                            .replace('/', '.')
                            .replace('\\', '.')
                            .replaceFirst("\\.class$", ""))
                    .map(ApiBoundaryTest::loadClass)
                    .forEach(classes::add);
        }
        return classes;
    }

    private static Path productionClassesRoot() throws Exception {
        Path codeSourceRoot = Path.of(ApiBoundaryTest.class.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        if ("test-classes".equals(codeSourceRoot.getFileName().toString())) {
            return codeSourceRoot.getParent().resolve("classes");
        }
        return codeSourceRoot;
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("cannot load API class " + className, e);
        }
    }

    private static final class GenericLeakFixture {
        public List<java.sql.Connection> leakedConnections() {
            return List.of();
        }
    }
}
