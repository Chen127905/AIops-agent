package com.cc.opsagent.ticket.application;

import com.cc.opsagent.ticket.domain.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class TicketStateMachineTest {

    private final TicketStateMachine stateMachine = new TicketStateMachine();

    @ParameterizedTest
    @CsvSource({
            "OPEN, TRIAGING",
            "TRIAGING, DIAGNOSING",
            "DIAGNOSING, WAITING_APPROVAL",
            "WAITING_APPROVAL, EXECUTING",
            "EXECUTING, VERIFYING",
            "VERIFYING, RESOLVED"
    })
    void allowsNormalPath(TicketStatus from, TicketStatus to) {
        assertThat(stateMachine.canTransition(from, to)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {
            "OPEN", "TRIAGING", "DIAGNOSING",
            "WAITING_APPROVAL", "EXECUTING", "VERIFYING"
    })
    void allowsEveryActiveStateToEnterExceptionalTerminalState(TicketStatus from) {
        assertThat(stateMachine.canTransition(from, TicketStatus.FAILED)).isTrue();
        assertThat(stateMachine.canTransition(from, TicketStatus.CANCELLED)).isTrue();
        assertThat(stateMachine.canTransition(from, TicketStatus.TIMEOUT)).isTrue();
        assertThat(stateMachine.canTransition(from, TicketStatus.MANUAL_REQUIRED)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {
            "RESOLVED", "FAILED", "CANCELLED", "TIMEOUT", "MANUAL_REQUIRED"
    })
    void rejectsLeavingTerminalState(TicketStatus from) {
        assertThat(stateMachine.canTransition(from, TicketStatus.TRIAGING)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "RESOLVED, FAILED",
            "FAILED, CANCELLED",
            "CANCELLED, TIMEOUT",
            "TIMEOUT, MANUAL_REQUIRED",
            "MANUAL_REQUIRED, RESOLVED"
    })
    void rejectsTransitionsBetweenTerminalStates(TicketStatus from, TicketStatus to) {
        assertThat(stateMachine.canTransition(from, to)).isFalse();
    }

    @Test
    void rejectsSkippingTheNormalPath() {
        assertThat(stateMachine.canTransition(TicketStatus.OPEN, TicketStatus.DIAGNOSING)).isFalse();
        assertThat(stateMachine.canTransition(TicketStatus.DIAGNOSING, TicketStatus.EXECUTING)).isFalse();
    }

    @Test
    void rejectsSameState() {
        assertThat(stateMachine.canTransition(TicketStatus.OPEN, TicketStatus.OPEN)).isFalse();
    }

    @Test
    void rejectsNullState() {
        assertThat(stateMachine.canTransition(null, TicketStatus.OPEN)).isFalse();
        assertThat(stateMachine.canTransition(TicketStatus.OPEN, null)).isFalse();
    }
}
