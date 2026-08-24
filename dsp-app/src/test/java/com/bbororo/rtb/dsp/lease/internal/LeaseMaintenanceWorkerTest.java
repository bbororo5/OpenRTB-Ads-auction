package com.bbororo.rtb.dsp.lease.internal;

import static com.bbororo.rtb.dsp.lease.api.LeaseMessages.LeaseRefillRejection.REGIONAL_LEDGER_UNAVAILABLE;
import static com.bbororo.rtb.dsp.lease.api.LeaseMessages.LeaseSettlementResult.APPLIED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bbororo.rtb.dsp.lease.api.LeaseRefill;
import com.bbororo.rtb.dsp.lease.api.LeaseSettlement;
import com.bbororo.rtb.dsp.lease.spi.RegionalBudgetLedger;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.InstallLease;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.LeaseSupplySnapshot;
import com.bbororo.rtb.dsp.lease.api.LeaseMessages.ClaimDueSettlements;
import com.bbororo.rtb.dsp.lease.api.LeaseMessages.LeaseRefillRejected;
import com.bbororo.rtb.dsp.lease.api.LeaseMessages.LeaseRefillResult;
import com.bbororo.rtb.dsp.lease.api.LeaseMessages.LeaseRefilled;
import com.bbororo.rtb.dsp.lease.api.LeaseMessages.LeaseSettlementAmounts;
import com.bbororo.rtb.dsp.lease.api.LeaseMessages.LeaseSettlementResult;
import com.bbororo.rtb.dsp.lease.api.LeaseMessages.RefillLease;
import com.bbororo.rtb.dsp.lease.api.LeaseMessages.SettlementWork;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LeaseMaintenanceWorkerTest {

    @Test
    void retriesTheSameLogicalRefillRequestAfterLedgerFailure() {
        var lifecycle = new RecordingLifecycle();
        lifecycle.refillResults.add(new LeaseRefillRejected(REGIONAL_LEDGER_UNAVAILABLE));
        lifecycle.refillResults.add(new LeaseRefilled(lease()));
        var worker = worker(lifecycle, new EmptyLedger());

        worker.runOnce().toCompletableFuture().join();
        worker.runOnce().toCompletableFuture().join();

        assertEquals(2, lifecycle.refills.size());
        assertEquals(lifecycle.refills.get(0).requestId(), lifecycle.refills.get(1).requestId());
    }

    @Test
    void claimsAndSettlesDueWorkInTheSameMaintenanceCycle() {
        var lifecycle = new RecordingLifecycle();
        lifecycle.refillResults.add(new LeaseRefilled(lease()));
        SettlementWork work = work();
        var ledger = new EmptyLedger() {
            @Override
            public CompletionStage<List<SettlementWork>> claimDue(ClaimDueSettlements command) {
                return CompletableFuture.completedFuture(List.of(work));
            }
        };

        var report = worker(lifecycle, ledger).runOnce().toCompletableFuture().join();

        assertEquals(1, report.settlementsClaimed());
        assertEquals(1, report.settlementsApplied());
        assertEquals(List.of(work), lifecycle.settlements);
    }

    private static LeaseMaintenanceWorker worker(
            RecordingLifecycle lifecycle,
            RegionalBudgetLedger ledger
    ) {
        var policy = new AdaptiveLeaseDemandPolicy(Duration.ofSeconds(1), 100, 1_000);
        AtomicInteger ids = new AtomicInteger();
        return new LeaseMaintenanceWorker(
                "instance-1", "worker-1", () -> List.of(snapshot()), policy,
                lifecycle, lifecycle, ledger, 10, Duration.ofSeconds(1),
                () -> "request-" + ids.incrementAndGet()
        );
    }

    private static LeaseSupplySnapshot snapshot() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new LeaseSupplySnapshot(
                "campaign-1", 0, 0, 0, 0, 0, 0, Optional.empty(), now
        );
    }

    private static InstallLease lease() {
        Instant issued = Instant.parse("2026-01-01T00:00:00Z");
        return new InstallLease(
                "lease-1", "campaign-1", 100, 1, issued, issued.plusSeconds(5)
        );
    }

    private static SettlementWork work() {
        Instant safe = Instant.parse("2026-01-01T00:00:10Z");
        return new SettlementWork(
                "lease-1", "campaign-1", "instance-1", 100, 1, 1,
                safe, safe.plusSeconds(1)
        );
    }

    private static final class RecordingLifecycle implements LeaseRefill, LeaseSettlement {
        private final List<RefillLease> refills = new ArrayList<>();
        private final List<SettlementWork> settlements = new ArrayList<>();
        private final List<LeaseRefillResult> refillResults = new ArrayList<>();

        @Override
        public CompletionStage<LeaseRefillResult> refill(RefillLease command) {
            refills.add(command);
            return CompletableFuture.completedFuture(refillResults.removeFirst());
        }

        @Override
        public CompletionStage<LeaseSettlementResult> settle(SettlementWork work) {
            settlements.add(work);
            return CompletableFuture.completedFuture(APPLIED);
        }
    }

    private static class EmptyLedger implements RegionalBudgetLedger {
        @Override public CompletionStage<LeaseRefillResult> issue(RefillLease command) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<List<SettlementWork>> claimDue(ClaimDueSettlements command) { return CompletableFuture.completedFuture(List.of()); }
        @Override public CompletionStage<LeaseSettlementResult> apply(
                SettlementWork work,
                LeaseSettlementAmounts settlement
        ) {
            throw new UnsupportedOperationException();
        }
    }
}
