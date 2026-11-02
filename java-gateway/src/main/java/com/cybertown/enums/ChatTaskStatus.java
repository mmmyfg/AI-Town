package com.cybertown.enums;

import java.util.EnumSet;
import java.util.Set;

public enum ChatTaskStatus {
    PENDING,
    PROCESSING,
    WAITING_RETRY,
    MODEL_DONE,
    COMPLETED,
    FAILED,
    TIMEOUT;

    private static final Set<ChatTaskStatus> TERMINAL = EnumSet.of(COMPLETED, FAILED, TIMEOUT);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public static ChatTaskStatus from(String value) {
        return ChatTaskStatus.valueOf(value == null ? PENDING.name() : value);
    }
}
