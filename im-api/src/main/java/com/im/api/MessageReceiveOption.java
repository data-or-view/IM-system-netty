package com.im.api;

public enum MessageReceiveOption {
    NORMAL(0),
    NOT_NOTIFY(1),
    NOT_RECEIVE(2);

    private final int code;

    MessageReceiveOption(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static MessageReceiveOption fromCode(int code) {
        for (MessageReceiveOption value : values()) {
            if (value.code == code) return value;
        }
        return NORMAL;
    }
}
