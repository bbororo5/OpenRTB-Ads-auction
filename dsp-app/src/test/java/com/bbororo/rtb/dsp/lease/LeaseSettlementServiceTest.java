package com.bbororo.rtb.dsp.lease;

import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult.APPLIED;
import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult.CONFLICT;
import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult.NOT_READY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementAmounts;
import com.bbororo.rtb.dsp.lease.LeaseMessages.SettlementWork;
import com.bbororo.rtb.dsp.outcome.api.LeaseOutcomeView;
import com.bbororo.rtb.dsp.outcome.api.LeaseOutcomeView.LeaseOutcomeSummary;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class LeaseSettlementServiceTest {

    @Test
    void waitsUntilAllReservationDeadlinesAreObservable() {
        var ledger = new CapturingLedger();
        var service = service(ledger, new LeaseOutcomeSummary(
                "lease-1", 1_000, 0, 1_000, 0, false
        ));

        assertEquals(NOT_READY, service.settle(work()).toCompletableFuture().join());
        assertNull(ledger.applied);
    }

    @Test
    void rejectsEvidenceForAnotherLeaseOrFaceValue() {
        var ledger = new CapturingLedger();
        var service = service(ledger, new LeaseOutcomeSummary(
                "lease-2", 1_000, 0, 1_000, 0, true
        ));

        assertEquals(CONFLICT, service.settle(work()).toCompletableFuture().join());
        assertNull(ledger.applied);
    }

    @Test
    void classifiesTheWholeFaceValueAndDelegatesIdempotentApplication() {
        var ledger = new CapturingLedger();
        var service = service(ledger, new LeaseOutcomeSummary(
                "lease-1", 1_000, 600, 300, 100, true
        ));

        assertEquals(APPLIED, service.settle(work()).toCompletableFuture().join());
        assertEquals(
                new LeaseSettlementAmounts("lease-1", 1, 1_000, 600, 300, 100),
                ledger.applied
        );
    }

    private static LeaseSettlementService service(
            CapturingLedger ledger,
            LeaseOutcomeSummary summary
    ) {
        LeaseOutcomeView reader = (leaseId, faceValueMicros, evaluatedAt) ->
                CompletableFuture.completedFuture(summary);
        return new LeaseSettlementService(ledger, reader);
    }

    private static SettlementWork work() {
        var safe = Instant.parse("2026-01-01T00:00:10Z");
        return new SettlementWork(
                "lease-1", "campaign-1", "instance-1", 1_000, 1, 1,
                safe, safe.plusSeconds(1)
        );
    }

    private static final class CapturingLedger implements RegionalBudgetLedger {
        private LeaseSettlementAmounts applied;

        @Override
        public java.util.concurrent.CompletionStage<LeaseMessages.LeaseRefillResult> issue(LeaseMessages.RefillLease command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.CompletionStage<List<SettlementWork>> claimDue(LeaseMessages.ClaimDueSettlements command) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public java.util.concurrent.CompletionStage<LeaseMessages.LeaseSettlementResult> apply(
                SettlementWork work,
                LeaseSettlementAmounts settlement
        ) {
            applied = settlement;
            return CompletableFuture.completedFuture(APPLIED);
        }
    }
}
