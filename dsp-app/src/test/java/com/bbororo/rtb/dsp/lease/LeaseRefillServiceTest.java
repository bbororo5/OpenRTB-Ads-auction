package com.bbororo.rtb.dsp.lease;

import static com.bbororo.rtb.dsp.spending.SpendingMessages.LeaseInstallResult.CAPACITY_EXCEEDED;
import static com.bbororo.rtb.dsp.spending.SpendingMessages.LeaseInstallResult.INSTALLED;
import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefillRejection.LOCAL_INSTALL_REJECTED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bbororo.rtb.dsp.spending.SpendingMessages.InstallLease;
import com.bbororo.rtb.dsp.spending.SpendingMessages.LeaseInstallResult;
import com.bbororo.rtb.dsp.spending.SpendingMessages.LeaseSupplySnapshot;
import com.bbororo.rtb.dsp.spending.SpendingMessages.PacingPosition;
import com.bbororo.rtb.dsp.spending.SpendingMessages.ReservationFinalization;
import com.bbororo.rtb.dsp.spending.SpendingMessages.ReservationResult;
import com.bbororo.rtb.dsp.spending.SpendingMessages.TryReserve;
import com.bbororo.rtb.dsp.spending.SpendingMessages.ReleaseReservation;
import com.bbororo.rtb.dsp.spending.SpendingMessages.CommitReservation;
import com.bbororo.rtb.dsp.spending.SpendingMessages.ExpireReservation;
import com.bbororo.rtb.dsp.spending.LocalSpendingAuthority;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefillRejected;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefilled;
import com.bbororo.rtb.dsp.lease.LeaseMessages.RefillLease;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LeaseRefillServiceTest {

    @Test
    void installsARecoverablyIssuedLeaseBeforeReportingSuccess() {
        var lease = lease();
        var local = new StubLocalBudget(INSTALLED);
        var service = new LeaseRefillService(
                new StubLedger(new LeaseRefilled(lease)), local, () -> 123L
        );

        var result = service.refill(refill()).toCompletableFuture().join();

        assertEquals(new LeaseRefilled(lease), result);
        assertEquals(lease, local.installed);
        assertEquals(123L, local.requestStartedNanos);
    }

    @Test
    void doesNotReportSuccessWhenLocalAuthorityCannotInstallTheLease() {
        var service = new LeaseRefillService(
                new StubLedger(new LeaseRefilled(lease())),
                new StubLocalBudget(CAPACITY_EXCEEDED),
                () -> 123L
        );

        var result = service.refill(refill()).toCompletableFuture().join();

        assertEquals(new LeaseRefillRejected(LOCAL_INSTALL_REJECTED), result);
    }

    @Test
    void capturesRequestStartBeforeWaitingForTheLedger() {
        AtomicLong monotonicNanos = new AtomicLong(100L);
        var local = new StubLocalBudget(INSTALLED);
        var ledger = new StubLedger(new LeaseRefilled(lease())) {
            @Override
            public java.util.concurrent.CompletionStage<LeaseMessages.LeaseRefillResult> issue(RefillLease command) {
                monotonicNanos.set(900L);
                return super.issue(command);
            }
        };
        var service = new LeaseRefillService(ledger, local, monotonicNanos::get);

        service.refill(refill()).toCompletableFuture().join();

        assertEquals(100L, local.requestStartedNanos);
    }

    private static RefillLease refill() {
        var now = Instant.parse("2026-01-01T00:00:00Z");
        var local = new LeaseSupplySnapshot(
                "campaign-1", 0, 0, 0, 0, 0, 0, Optional.empty(), now
        );
        return new RefillLease("request-1", "instance-1", local, 1_000);
    }

    private static InstallLease lease() {
        var issued = Instant.parse("2026-01-01T00:00:00Z");
        return new InstallLease("lease-1", "campaign-1", 1_000, 1, issued, issued.plusSeconds(5));
    }

    private static class StubLedger implements RegionalBudgetLedger {
        private final LeaseMessages.LeaseRefillResult result;

        private StubLedger(LeaseMessages.LeaseRefillResult result) {
            this.result = result;
        }

        @Override
        public java.util.concurrent.CompletionStage<LeaseMessages.LeaseRefillResult> issue(RefillLease command) {
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public java.util.concurrent.CompletionStage<List<LeaseMessages.SettlementWork>> claimDue(LeaseMessages.ClaimDueSettlements command) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public java.util.concurrent.CompletionStage<LeaseMessages.LeaseSettlementResult> apply(
                LeaseMessages.SettlementWork work,
                LeaseMessages.LeaseSettlementAmounts settlement
        ) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StubLocalBudget implements LocalSpendingAuthority {
        private final LeaseInstallResult result;
        private InstallLease installed;
        private long requestStartedNanos;

        private StubLocalBudget(LeaseInstallResult result) {
            this.result = result;
        }

        @Override public ReservationResult tryReserve(TryReserve command) { throw new UnsupportedOperationException(); }
        @Override public ReservationFinalization release(ReleaseReservation command) { throw new UnsupportedOperationException(); }
        @Override public ReservationFinalization commit(CommitReservation command) { throw new UnsupportedOperationException(); }
        @Override public ReservationFinalization expire(ExpireReservation command) { throw new UnsupportedOperationException(); }
        @Override public PacingPosition positionOf(String campaignId) { throw new UnsupportedOperationException(); }
        @Override public List<LeaseSupplySnapshot> supplySnapshots() { return List.of(); }

        @Override
        public LeaseInstallResult install(InstallLease command, long requestStartedNanos) {
            installed = command;
            this.requestStartedNanos = requestStartedNanos;
            return result;
        }
    }
}
