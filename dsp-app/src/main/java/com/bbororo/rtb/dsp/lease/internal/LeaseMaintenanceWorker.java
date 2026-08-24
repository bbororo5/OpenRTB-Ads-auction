package com.bbororo.rtb.dsp.lease.internal;

import static com.bbororo.rtb.dsp.lease.api.LeaseMessages.LeaseRefillRejection.LOCAL_INSTALL_REJECTED;
import static com.bbororo.rtb.dsp.lease.api.LeaseMessages.LeaseRefillRejection.REGIONAL_LEDGER_UNAVAILABLE;
import static com.bbororo.rtb.dsp.lease.api.LeaseMessages.LeaseSettlementResult.ALREADY_APPLIED;
import static com.bbororo.rtb.dsp.lease.api.LeaseMessages.LeaseSettlementResult.APPLIED;

import com.bbororo.rtb.dsp.lease.api.LeaseMessages;
import com.bbororo.rtb.dsp.lease.api.LeaseRefill;
import com.bbororo.rtb.dsp.lease.api.LeaseSettlement;
import com.bbororo.rtb.dsp.lease.spi.RegionalBudgetLedger;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.LeaseSupplySnapshot;
import com.bbororo.rtb.dsp.spending.api.LocalLeaseSupplyView;
import com.bbororo.rtb.dsp.lease.api.LeaseMessages.ClaimDueSettlements;
import com.bbororo.rtb.dsp.lease.api.LeaseMessages.LeaseRefillRejected;
import com.bbororo.rtb.dsp.lease.api.LeaseMessages.LeaseRefilled;
import com.bbororo.rtb.dsp.lease.api.LeaseMessages.RefillLease;
import com.bbororo.rtb.dsp.lease.api.LeaseMessages.SettlementWork;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** 한 DSP 인스턴스에서 보충과 만료 리스 정산을 겹치지 않게 한 주기 실행한다. */
public final class LeaseMaintenanceWorker {

    private final String instanceId;
    private final String workerId;
    private final LocalLeaseSupplyView supplyView;
    private final AdaptiveLeaseDemandPolicy demandPolicy;
    private final LeaseRefill refill;
    private final LeaseSettlement settlement;
    private final RegionalBudgetLedger ledger;
    private final int settlementBatchSize;
    private final Duration settlementClaimDuration;
    private final Supplier<String> requestIds;
    private final Map<String, LeaseSupplySnapshot> previousSnapshots = new HashMap<>();
    private final Map<String, RefillLease> pendingRefills = new HashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();

    public LeaseMaintenanceWorker(
            String instanceId,
            String workerId,
            LocalLeaseSupplyView supplyView,
            AdaptiveLeaseDemandPolicy demandPolicy,
            LeaseRefill refill,
            LeaseSettlement settlement,
            RegionalBudgetLedger ledger,
            int settlementBatchSize,
            Duration settlementClaimDuration
    ) {
        this(
                instanceId, workerId, supplyView, demandPolicy, refill, settlement, ledger,
                settlementBatchSize, settlementClaimDuration,
                () -> UUID.randomUUID().toString()
        );
    }

    LeaseMaintenanceWorker(
            String instanceId,
            String workerId,
            LocalLeaseSupplyView supplyView,
            AdaptiveLeaseDemandPolicy demandPolicy,
            LeaseRefill refill,
            LeaseSettlement settlement,
            RegionalBudgetLedger ledger,
            int settlementBatchSize,
            Duration settlementClaimDuration,
            Supplier<String> requestIds
    ) {
        this.instanceId = requireNonBlank(instanceId, "instanceId");
        this.workerId = requireNonBlank(workerId, "workerId");
        this.supplyView = Objects.requireNonNull(supplyView, "supplyView");
        this.demandPolicy = Objects.requireNonNull(demandPolicy, "demandPolicy");
        this.refill = Objects.requireNonNull(refill, "refill");
        this.settlement = Objects.requireNonNull(settlement, "settlement");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        if (settlementBatchSize <= 0) {
            throw new IllegalArgumentException("settlementBatchSize must be positive");
        }
        this.settlementBatchSize = settlementBatchSize;
        this.settlementClaimDuration = Objects.requireNonNull(
                settlementClaimDuration, "settlementClaimDuration"
        );
        if (settlementClaimDuration.isZero() || settlementClaimDuration.isNegative()) {
            throw new IllegalArgumentException("settlementClaimDuration must be positive");
        }
        this.requestIds = Objects.requireNonNull(requestIds, "requestIds");
    }

    public CompletionStage<MaintenanceReport> runOnce() {
        if (!running.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(MaintenanceReport.skippedReport());
        }
        try {
            return refillAll(supplyView.supplySnapshots())
                    .thenCompose(refills -> ledger.claimDue(new ClaimDueSettlements(
                            workerId, settlementBatchSize, settlementClaimDuration
                    )).thenCompose(work -> settleAll(work, refills)))
                    .whenComplete((ignored, failure) -> running.set(false));
        } catch (RuntimeException failure) {
            running.set(false);
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletionStage<RefillCount> refillAll(List<LeaseSupplySnapshot> snapshots) {
        CompletionStage<RefillCount> stage = CompletableFuture.completedFuture(new RefillCount(0, 0));
        for (LeaseSupplySnapshot snapshot : snapshots) {
            RefillLease pending = pendingRefills.get(snapshot.campaignId());
            long requested = pending == null
                    ? demandPolicy.requestedMicros(
                            snapshot,
                            Optional.ofNullable(previousSnapshots.get(snapshot.campaignId()))
                    )
                    : pending.requestedMicros();
            previousSnapshots.put(snapshot.campaignId(), snapshot);
            if (requested == 0L && pending == null) {
                continue;
            }
            RefillLease command = pending == null
                    ? new RefillLease(requestIds.get(), instanceId, snapshot, requested)
                    : pending;
            pendingRefills.put(snapshot.campaignId(), command);
            stage = stage.thenCompose(count -> refill.refill(command).thenApply(result -> {
                boolean succeeded = result instanceof LeaseRefilled;
                if (succeeded || !isRetryable(result)) {
                    pendingRefills.remove(snapshot.campaignId());
                }
                return count.add(succeeded);
            }));
        }
        return stage;
    }

    private CompletionStage<MaintenanceReport> settleAll(
            List<SettlementWork> work,
            RefillCount refills
    ) {
        CompletionStage<Integer> applied = CompletableFuture.completedFuture(0);
        for (SettlementWork item : work) {
            applied = applied.thenCompose(count -> settlement.settle(item).thenApply(result ->
                    count + ((result == APPLIED || result == ALREADY_APPLIED) ? 1 : 0)
            ));
        }
        return applied.thenApply(count -> new MaintenanceReport(
                refills.attempted(), refills.succeeded(), work.size(), count, false
        ));
    }

    private static boolean isRetryable(LeaseMessages.LeaseRefillResult result) {
        return result instanceof LeaseRefillRejected rejected
                && (rejected.reason() == REGIONAL_LEDGER_UNAVAILABLE
                || rejected.reason() == LOCAL_INSTALL_REJECTED);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public record MaintenanceReport(
            int refillAttempts,
            int refillsSucceeded,
            int settlementsClaimed,
            int settlementsApplied,
            boolean skipped
    ) {
        static MaintenanceReport skippedReport() {
            return new MaintenanceReport(0, 0, 0, 0, true);
        }
    }

    private record RefillCount(int attempted, int succeeded) {
        RefillCount add(boolean success) {
            return new RefillCount(attempted + 1, succeeded + (success ? 1 : 0));
        }
    }
}
