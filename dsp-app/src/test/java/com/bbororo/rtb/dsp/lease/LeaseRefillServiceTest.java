package com.bbororo.rtb.dsp.lease;

import static com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseInstallResult.CAPACITY_EXCEEDED;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseInstallResult.INSTALLED;
import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefillRejection.LOCAL_INSTALL_REJECTED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bbororo.rtb.dsp.budget.BudgetMessages.InstallLease;
import com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseInstallResult;
import com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseSupplySnapshot;
import com.bbororo.rtb.dsp.budget.BudgetMessages.PacingPosition;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationFinalization;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationResult;
import com.bbororo.rtb.dsp.budget.BudgetMessages.TryReserve;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReleaseReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.CommitReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ExpireReservation;
import com.bbororo.rtb.dsp.budget.LocalBudgetAuthority;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefillRejected;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefilled;
import com.bbororo.rtb.dsp.lease.LeaseMessages.RefillLease;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class LeaseRefillServiceTest {

    @Test
    void installsARecoverablyIssuedLeaseBeforeReportingSuccess() {
        var lease = lease();
        var local = new StubLocalBudget(INSTALLED);
        var service = new LeaseRefillService(new StubLedger(new LeaseRefilled(lease)), local);

        var result = service.refill(refill()).toCompletableFuture().join();

        assertEquals(new LeaseRefilled(lease), result);
        assertEquals(lease, local.installed);
    }

    @Test
    void doesNotReportSuccessWhenLocalAuthorityCannotInstallTheLease() {
        var service = new LeaseRefillService(
                new StubLedger(new LeaseRefilled(lease())),
                new StubLocalBudget(CAPACITY_EXCEEDED)
        );

        var result = service.refill(refill()).toCompletableFuture().join();

        assertEquals(new LeaseRefillRejected(LOCAL_INSTALL_REJECTED), result);
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

    private static final class StubLedger implements RegionalBudgetLedger {
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
        public java.util.concurrent.CompletionStage<LeaseMessages.LeaseSettlementResult> apply(LeaseMessages.SettlementWork work, LeaseMessages.LeaseSettlement settlement) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StubLocalBudget implements LocalBudgetAuthority {
        private final LeaseInstallResult result;
        private InstallLease installed;

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
        public LeaseInstallResult install(InstallLease command) {
            installed = command;
            return result;
        }
    }
}
