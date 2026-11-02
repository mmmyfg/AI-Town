package com.cybertown.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatTaskStatusTests {
    @Test
    void onlyFinalStatesAreTerminal() {
        assertThat(ChatTaskStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(ChatTaskStatus.FAILED.isTerminal()).isTrue();
        assertThat(ChatTaskStatus.TIMEOUT.isTerminal()).isTrue();
        assertThat(ChatTaskStatus.PROCESSING.isTerminal()).isFalse();
        assertThat(ChatTaskStatus.WAITING_RETRY.isTerminal()).isFalse();
    }
}
