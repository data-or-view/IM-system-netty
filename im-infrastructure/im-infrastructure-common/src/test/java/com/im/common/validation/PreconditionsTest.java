package com.im.common.validation;

import com.im.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreconditionsTest {

    @Test
    void requireNonNullReturnsValueOrThrowsValidationException() {
        assertEquals("u1", Preconditions.requireNonNull("u1", "userId"));

        ValidationException thrown = assertThrows(ValidationException.class,
                () -> Preconditions.requireNonNull(null, "userId"));
        assertEquals("userId is required", thrown.getDetail());
    }

    @Test
    void requireTextRejectsNullBlankAndReturnsOriginalValue() {
        assertEquals(" Alice ", Preconditions.requireText(" Alice ", "nickname"));

        assertEquals("nickname is required", assertThrows(ValidationException.class,
                () -> Preconditions.requireText(null, "nickname")).getDetail());
        assertEquals("nickname is required", assertThrows(ValidationException.class,
                () -> Preconditions.requireText("  ", "nickname")).getDetail());
    }

    @Test
    void requirePositiveRejectsZeroAndNegativeValues() {
        assertEquals(1, Preconditions.requirePositive(1, "limit"));

        assertEquals("limit must be positive", assertThrows(ValidationException.class,
                () -> Preconditions.requirePositive(0, "limit")).getDetail());
        assertEquals("limit must be positive", assertThrows(ValidationException.class,
                () -> Preconditions.requirePositive(-1, "limit")).getDetail());
    }
}
