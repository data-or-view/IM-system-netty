package com.im.common.id;

/**
 * Centralized business ID prefixes used across server-side domain objects.
 */
public final class IdPrefix {

    public static final String GROUP = "grp";
    public static final String ROOM = "room";
    public static final String MESSAGE = "msg";
    public static final String SESSION = "sess";

    private IdPrefix() {
        // utility class
    }
}
