package org.example.reservation;

import org.example.reservation.service.ApprovalWorkflow;
import org.example.reservation.service.ReservationService;
import org.example.reservation.web.ReservationController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C10 situation 3: the technical-failure handler of the Reservation API.
 *
 * The real outage was executed against PostgreSQL (docs/evidence-and-evolution.md, C10); this
 * test pins the contract of the minimal log record and the response so it cannot silently
 * regress: 503 TECHNICAL_FAILURE, one ERROR line naming operation, actor, type and outcome,
 * and no personal data.
 */
@WebMvcTest(ReservationController.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("C10 - technical failure: 503 + one diagnostic log line")
class C10TechnicalFailureLoggingTest {

    @Autowired MockMvc mvc;
    @MockitoBean ReservationService reservationService;
    @MockitoBean ApprovalWorkflow approvalWorkflow;

    @Test
    @DisplayName("C10-3: DB unreachable before the transaction starts -> 503, log names op/actor/type, outcome NOT_STARTED")
    void databaseUnreachable(CapturedOutput output) throws Exception {
        when(reservationService.confirm(any(), any())).thenThrow(new CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction", new ConnectException("Connection refused")));

        mvc.perform(post("/reservations/42/confirm").header("X-Actor-Id", "7"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TECHNICAL_FAILURE"));

        assertThat(output).contains("TECHNICAL_FAILURE op=POST /reservations/42/confirm actor=7")
                .contains("type=CannotCreateTransactionException")
                .contains("cause=ConnectException: Connection refused")
                .contains("outcome=NOT_STARTED");
    }

    @Test
    @DisplayName("C10-3: failure inside the transaction -> 503, outcome UNKNOWN (client must re-read, not retry blindly)")
    void failureInsideTransaction(CapturedOutput output) throws Exception {
        when(reservationService.cancel(any(), any())).thenThrow(new DataAccessResourceFailureException("I/O error"));

        mvc.perform(post("/reservations/5/cancel").header("X-Actor-Id", "1"))
                .andExpect(status().isServiceUnavailable());

        assertThat(output).contains("op=POST /reservations/5/cancel actor=1").contains("outcome=UNKNOWN");
    }

    @Test
    @DisplayName("C10 regression: a concurrent-change conflict is still 409 INVALID_STATE, not a technical failure")
    void optimisticLockStillConflict(CapturedOutput output) throws Exception {
        when(reservationService.cancel(any(), any())).thenThrow(new OptimisticLockingFailureException("stale"));

        mvc.perform(post("/reservations/5/cancel").header("X-Actor-Id", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        assertThat(output).doesNotContain("TECHNICAL_FAILURE");
    }
}
