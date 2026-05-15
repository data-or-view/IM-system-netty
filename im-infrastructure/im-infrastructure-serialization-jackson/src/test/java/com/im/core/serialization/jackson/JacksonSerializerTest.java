package com.im.core.serialization.jackson;

import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class JacksonSerializerTest {

    @Test
    void shouldSerializeAndDeserializeString() {
        var ser = new JacksonSerializer<String>();
        String raw = ser.serialize("hello");
        assertEquals("\"hello\"", raw);
        assertEquals("hello", ser.deserialize(raw, String.class));
    }

    @Test
    void shouldSerializeAndDeserializeInteger() {
        var ser = new JacksonSerializer<Integer>();
        String raw = ser.serialize(42);
        assertEquals("42", raw);
        assertEquals(42, ser.deserialize(raw, Integer.class));
    }

    @Test
    void shouldSerializeAndDeserializePojo() {
        var ser = new JacksonSerializer<TestUser>();
        var user = new TestUser("alice", 30);
        String raw = ser.serialize(user);
        TestUser result = ser.deserialize(raw, TestUser.class);
        assertEquals(user, result);
    }

    @Test
    void shouldSerializeBoolean() {
        var ser = new JacksonSerializer<Boolean>();
        assertEquals("true", ser.serialize(true));
        assertTrue(ser.deserialize("true", Boolean.class));
    }

    @Test
    void shouldSerializeNullToNull() {
        var ser = new JacksonSerializer<>();
        assertDoesNotThrow(() -> ser.serialize(null));
    }

    @Test
    void shouldThrowOnDeserializeInvalidJson() {
        var ser = new JacksonSerializer<String>();
        assertThrows(IllegalArgumentException.class,
                () -> ser.deserialize("{invalid", String.class));
    }

    @Test
    void shouldThrowOnDeserializeTypeMismatch() {
        var ser = new JacksonSerializer<Integer>();
        assertThrows(IllegalArgumentException.class,
                () -> ser.deserialize("\"hello\"", Integer.class));
    }

    // ── test POJO ──

    static class TestUser {
        private String name;
        private int age;

        // Jackson needs no-arg constructor
        public TestUser() {}

        public TestUser(String name, int age) {
            this.name = name;
            this.age = age;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TestUser u)) return false;
            return age == u.age && Objects.equals(name, u.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, age);
        }
    }
}
