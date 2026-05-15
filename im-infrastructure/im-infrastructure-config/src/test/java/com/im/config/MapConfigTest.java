package com.im.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MapConfigTest {

    private final MapConfig config = new MapConfig(Map.of(
            "host", "localhost",
            "port", "8080",
            "timeout", "30000",
            "enabled", "true",
            "maxWait", "PT30S",
            "empty", "",
            "one", "1",
            "zero", "0"
    ));

    // ========== String ==========

    @Test
    void shouldReturnStringValue() {
        assertEquals(Optional.of("localhost"), config.getString("host"));
    }

    @Test
    void shouldReturnStringDefaultWhenMissing() {
        assertEquals("default", config.getString("nonexistent", "default"));
    }

    @Test
    void shouldReturnEmptyForMissingKey() {
        assertTrue(config.getString("nonexistent").isEmpty());
    }

    // ========== Integer ==========

    @Test
    void shouldReturnIntValue() {
        assertEquals(Optional.of(8080), config.getInt("port"));
    }

    @Test
    void shouldReturnIntDefaultWhenMissing() {
        assertEquals(42, config.getInt("nonexistent", 42));
    }

    @Test
    void shouldReturnEmptyForInvalidInt() {
        assertTrue(config.getInt("host").isEmpty());
    }

    // ========== Long ==========

    @Test
    void shouldReturnLongValue() {
        assertEquals(Optional.of(30000L), config.getLong("timeout"));
    }

    @Test
    void shouldReturnLongDefaultWhenMissing() {
        assertEquals(99L, config.getLong("nonexistent", 99L));
    }

    // ========== Boolean ==========

    @Test
    void shouldReturnTrueForExplicitTrue() {
        assertEquals(Optional.of(true), config.getBoolean("enabled"));
    }

    @Test
    void shouldTreatOneAsTrue() {
        assertEquals(Optional.of(true), config.getBoolean("one"));
    }

    @Test
    void shouldTreatZeroAsFalse() {
        assertEquals(Optional.of(false), config.getBoolean("zero"));
    }

    @Test
    void shouldReturnFalseForFalseLiteral() {
        var cfg = new MapConfig(Map.of("v", "false"));
        assertEquals(Optional.of(false), cfg.getBoolean("v"));
    }

    @Test
    void shouldReturnBooleanDefaultWhenMissing() {
        assertTrue(config.getBoolean("nonexistent", true));
    }

    // ========== Duration ==========

    @Test
    void shouldReturnIsoDuration() {
        assertEquals(Optional.of(Duration.ofSeconds(30)), config.getDuration("maxWait"));
    }

    @Test
    void shouldReturnShorthandDuration() {
        var cfg = new MapConfig(Map.of("v", "30s"));
        assertEquals(Optional.of(Duration.ofSeconds(30)), cfg.getDuration("v"));
    }

    @Test
    void shouldReturnDurationDefaultWhenMissing() {
        assertEquals(Duration.ofMinutes(1), config.getDuration("nonexistent", Duration.ofMinutes(1)));
    }

    // ========== hasKey ==========

    @Test
    void shouldReturnTrueForExistingKey() {
        assertTrue(config.hasKey("host"));
    }

    @Test
    void shouldReturnFalseForMissingKey() {
        assertFalse(config.hasKey("nonexistent"));
    }

    @Test
    void shouldReturnTrueForEmptyStringValue() {
        assertTrue(config.hasKey("empty"));
    }

    // ========== Required ==========

    @Test
    void shouldReturnRequiredString() {
        assertEquals("localhost", config.getRequiredString("host"));
    }

    @Test
    void shouldThrowOnMissingRequiredString() {
        assertThrows(ConfigException.class, () -> config.getRequiredString("nonexistent"));
    }

    @Test
    void shouldThrowOnMissingRequiredInt() {
        assertThrows(ConfigException.class, () -> config.getRequiredInt("nonexistent"));
    }

    // ========== dump ==========

    @Test
    void dumpShouldReturnAllEntries() {
        assertEquals(8, config.dump().size());
    }
}
