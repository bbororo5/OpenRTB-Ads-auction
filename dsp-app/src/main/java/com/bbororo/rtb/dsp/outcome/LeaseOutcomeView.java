package com.bbororo.rtb.dsp.outcome;

import static com.bbororo.rtb.dsp.contract.ContractChecks.requireNonBlank;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requireNonNegative;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Reservation Outcome이 제공하는 리스별 canonical 금액 결과 투영이다. */
public interface LeaseOutcomeView {

    CompletionStage<LeaseOutcomeSummary> summarize(
            String leaseId,
            long faceValueMicros,
            Instant safeRecoveryAt
    );

    record LeaseOutcomeSummary(
            String leaseId,
            long faceValueMicros,
            long committedMicros,
            long returnableMicros,
            long quarantinedMicros,
            boolean safeRecoveryReached
    ) {
        public LeaseOutcomeSummary {
            leaseId = requireNonBlank(leaseId, "leaseId");
            requireNonNegative(faceValueMicros, "faceValueMicros");
            requireNonNegative(committedMicros, "committedMicros");
            requireNonNegative(returnableMicros, "returnableMicros");
            requireNonNegative(quarantinedMicros, "quarantinedMicros");
            long classified = Math.addExact(
                    Math.addExact(committedMicros, returnableMicros),
                    quarantinedMicros
            );
            if (classified != faceValueMicros) {
                throw new IllegalArgumentException("lease outcomes must preserve face value");
            }
        }
    }
}
