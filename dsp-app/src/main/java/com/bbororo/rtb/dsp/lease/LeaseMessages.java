package com.bbororo.rtb.dsp.lease;

import static com.bbororo.rtb.dsp.contract.ContractChecks.requireAfter;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requireNonBlank;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requireNonNegative;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requirePositive;

import com.bbororo.rtb.dsp.spending.api.SpendingMessages.InstallLease;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.LeaseSupplySnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** DSP 로컬 리스의 가변 보충과 자동 정산 메시지다. */
public final class LeaseMessages {

    private LeaseMessages() {
    }

    public record RefillLease(
            String requestId,
            String instanceId,
            LeaseSupplySnapshot localPosition,
            long requestedMicros
    ) {
        public RefillLease {
            requestId = requireNonBlank(requestId, "requestId");
            instanceId = requireNonBlank(instanceId, "instanceId");
            Objects.requireNonNull(localPosition, "localPosition");
            requirePositive(requestedMicros, "requestedMicros");
        }

        public String campaignId() {
            return localPosition.campaignId();
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

    public record ClaimDueSettlements(
            String workerId,
            int limit,
            Duration claimDuration
    ) {
        public ClaimDueSettlements {
            workerId = requireNonBlank(workerId, "workerId");
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
            Objects.requireNonNull(claimDuration, "claimDuration");
            if (claimDuration.isZero() || claimDuration.isNegative()) {
                throw new IllegalArgumentException("claimDuration must be positive");
            }
        }
    }

    public record SettlementWork(
            String leaseId,
            String campaignId,
            String ownerInstanceId,
            long faceValueMicros,
            long settlementGeneration,
            long claimGeneration,
            Instant safeRecoveryAt,
            Instant claimUntil
    ) {
        public SettlementWork {
            leaseId = requireNonBlank(leaseId, "leaseId");
            campaignId = requireNonBlank(campaignId, "campaignId");
            ownerInstanceId = requireNonBlank(ownerInstanceId, "ownerInstanceId");
            requirePositive(faceValueMicros, "faceValueMicros");
            requirePositive(settlementGeneration, "settlementGeneration");
            requirePositive(claimGeneration, "claimGeneration");
            requireAfter(safeRecoveryAt, claimUntil, "claimUntil");
        }
    }

    public record LeaseSettlementAmounts(
            String leaseId,
            long settlementGeneration,
            long faceValueMicros,
            long committedMicros,
            long returnedMicros,
            long quarantinedMicros
    ) {
        public LeaseSettlementAmounts {
            leaseId = requireNonBlank(leaseId, "leaseId");
            requirePositive(settlementGeneration, "settlementGeneration");
            requireNonNegative(faceValueMicros, "faceValueMicros");
            requireNonNegative(committedMicros, "committedMicros");
            requireNonNegative(returnedMicros, "returnedMicros");
            requireNonNegative(quarantinedMicros, "quarantinedMicros");
            requireFaceValue(faceValueMicros, committedMicros, returnedMicros, quarantinedMicros);
        }
    }

    public enum LeaseRefillRejection {
        REGIONAL_BUDGET_UNAVAILABLE,
        PACING_LIMIT_REACHED,
        REGIONAL_LEDGER_UNAVAILABLE,
        LOCAL_INSTALL_REJECTED,
        STALE_REQUEST
    }

    public enum LeaseSettlementResult {
        APPLIED,
        ALREADY_APPLIED,
        NOT_READY,
        STALE_CLAIM,
        TEMPORARILY_UNAVAILABLE,
        CONFLICT
    }

    private static void requireFaceValue(long face, long committed, long returned, long quarantined) {
        long classified = Math.addExact(Math.addExact(committed, returned), quarantined);
        if (classified != face) {
            throw new IllegalArgumentException("lease amounts must preserve face value");
        }
    }
}
