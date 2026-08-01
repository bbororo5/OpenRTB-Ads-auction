package com.bbororo.rtb.dsp.lease;

import static com.bbororo.rtb.dsp.contract.ContractChecks.requireNonBlank;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requireNonNegative;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requirePositive;

import com.bbororo.rtb.dsp.budget.BudgetMessages.InstallLease;
import java.time.Instant;
import java.util.Objects;

/** DSP 로컬 리스의 보충·집계·정산 메시지다. */
public final class LeaseMessages {

    private LeaseMessages() {
    }

    public record RefillLease(
            String requestId,
            String instanceId,
            String campaignId,
            long requestedMicros,
            Instant requestedAt
    ) {
        public RefillLease {
            requestId = requireNonBlank(requestId, "requestId");
            instanceId = requireNonBlank(instanceId, "instanceId");
            campaignId = requireNonBlank(campaignId, "campaignId");
            requirePositive(requestedMicros, "requestedMicros");
            Objects.requireNonNull(requestedAt, "requestedAt");
        }
    }

    public sealed interface LeaseRefillResult permits LeaseRefilled, LeaseRefillRejected {
    }

    public record LeaseRefilled(InstallLease lease) implements LeaseRefillResult {
        public LeaseRefilled {
            Objects.requireNonNull(lease, "lease");
        }
    }

    public record LeaseRefillRejected(LeaseRefillRejection reason) implements LeaseRefillResult {
        public LeaseRefillRejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record SettleLease(String leaseId, long settlementGeneration, Instant requestedAt) {
        public SettleLease {
            leaseId = requireNonBlank(leaseId, "leaseId");
            requirePositive(settlementGeneration, "settlementGeneration");
            Objects.requireNonNull(requestedAt, "requestedAt");
        }
    }

    public record LeaseUsageSummary(
            String leaseId,
            long faceValueMicros,
            long committedMicros,
            long returnableMicros,
            long quarantinedMicros,
            boolean allReservationDeadlinesPassed
    ) {
        public LeaseUsageSummary {
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
                throw new IllegalArgumentException("lease usage must preserve face value");
            }
        }
    }

    public record LeaseSettlement(
            String leaseId,
            long settlementGeneration,
            long faceValueMicros,
            long committedMicros,
            long returnedMicros,
            long quarantinedMicros
    ) {
        public LeaseSettlement {
            leaseId = requireNonBlank(leaseId, "leaseId");
            requirePositive(settlementGeneration, "settlementGeneration");
            requireNonNegative(faceValueMicros, "faceValueMicros");
            requireNonNegative(committedMicros, "committedMicros");
            requireNonNegative(returnedMicros, "returnedMicros");
            requireNonNegative(quarantinedMicros, "quarantinedMicros");
            long classified = Math.addExact(
                    Math.addExact(committedMicros, returnedMicros),
                    quarantinedMicros
            );
            if (classified != faceValueMicros) {
                throw new IllegalArgumentException("lease settlement must preserve face value");
            }
        }
    }

    public enum LeaseRefillRejection {
        REGIONAL_BUDGET_UNAVAILABLE,
        PACING_LIMIT_REACHED,
        REGIONAL_LEDGER_UNAVAILABLE,
        STALE_REQUEST
    }

    public enum LeaseSettlementResult {
        APPLIED,
        ALREADY_APPLIED,
        NOT_READY,
        TEMPORARILY_UNAVAILABLE,
        CONFLICT
    }
}
