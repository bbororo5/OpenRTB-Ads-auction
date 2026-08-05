package com.bbororo.rtb.dsp.lease;

import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult.CONFLICT;
import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult.NOT_READY;

import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlement;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseUsageSummary;
import com.bbororo.rtb.dsp.lease.LeaseMessages.SettlementWork;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 만료 리스의 내구 사건을 집계해 같은 정산 세대로 원장에 한 번 반영한다. */
public final class LeaseSettlementService {

    private final RegionalBudgetLedger ledger;
    private final LeaseEventReader eventReader;

    public LeaseSettlementService(RegionalBudgetLedger ledger, LeaseEventReader eventReader) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.eventReader = Objects.requireNonNull(eventReader, "eventReader");
    }

    public CompletionStage<LeaseSettlementResult> settle(SettlementWork work) {
        Objects.requireNonNull(work, "work");
        return eventReader.summarize(
                        work.leaseId(), work.faceValueMicros(), work.safeRecoveryAt()
                )
                .thenCompose(summary -> apply(work, summary));
    }

    private CompletionStage<LeaseSettlementResult> apply(
            SettlementWork work,
            LeaseUsageSummary summary
    ) {
        if (!summary.allReservationDeadlinesPassed()) {
            return CompletableFuture.completedFuture(NOT_READY);
        }
        if (!summary.leaseId().equals(work.leaseId())
                || summary.faceValueMicros() != work.faceValueMicros()) {
            return CompletableFuture.completedFuture(CONFLICT);
        }
        var settlement = new LeaseSettlement(
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
