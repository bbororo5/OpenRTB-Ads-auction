package com.bbororo.rtb.dsp.lease;

import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult.CONFLICT;
import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult.NOT_READY;

import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementAmounts;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult;
import com.bbororo.rtb.dsp.lease.LeaseMessages.SettlementWork;
import com.bbororo.rtb.dsp.outcome.LeaseOutcomeView;
import com.bbororo.rtb.dsp.outcome.LeaseOutcomeView.LeaseOutcomeSummary;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 만료 리스의 내구 사건을 집계해 같은 정산 세대로 원장에 한 번 반영한다. */
public final class LeaseSettlementService implements LeaseSettlement {

    private final RegionalBudgetLedger ledger;
    private final LeaseOutcomeView outcomeView;

    public LeaseSettlementService(RegionalBudgetLedger ledger, LeaseOutcomeView outcomeView) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.outcomeView = Objects.requireNonNull(outcomeView, "outcomeView");
    }

    @Override
    public CompletionStage<LeaseSettlementResult> settle(SettlementWork work) {
        Objects.requireNonNull(work, "work");
        return outcomeView.summarize(
                        work.leaseId(), work.faceValueMicros(), work.safeRecoveryAt()
                )
                .thenCompose(summary -> apply(work, summary));
    }

    private CompletionStage<LeaseSettlementResult> apply(
            SettlementWork work,
            LeaseOutcomeSummary summary
    ) {
        if (!summary.safeRecoveryReached()) {
            return CompletableFuture.completedFuture(NOT_READY);
        }
        if (!summary.leaseId().equals(work.leaseId())
                || summary.faceValueMicros() != work.faceValueMicros()) {
            return CompletableFuture.completedFuture(CONFLICT);
        }
        var settlement = new LeaseSettlementAmounts(
                work.leaseId(),
                work.settlementGeneration(),
                summary.faceValueMicros(),
                summary.committedMicros(),
                summary.returnableMicros(),
                summary.quarantinedMicros()
        );
        return ledger.apply(work, settlement);
    }
}
