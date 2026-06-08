package com.im.common.validation;

import com.im.common.exception.ValidationException;

public final class Preconditions {

    private Preconditions() {
    }

    public static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new ValidationException(name + " is required");
        }
        return value;
    }

    public static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(name + " is required");
        }
        return value;
    }

    public static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new ValidationException(name + " must be positive");
        }
        return value;
    }

    public static long requirePositive(long value, String name) {
        if (value <= 0) {
            throw new ValidationException(name + " must be positive");
        }
        return value;
    }
}
