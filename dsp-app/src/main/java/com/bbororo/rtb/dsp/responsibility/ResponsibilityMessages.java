package com.bbororo.rtb.dsp.responsibility;

import static com.bbororo.rtb.dsp.contract.ContractChecks.requireNonBlank;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requirePositive;

import java.time.Instant;
import java.util.Objects;

/** 전역 예비액을 리전 책임액으로 이전하는 제어 메시지다. */
public final class ResponsibilityMessages {

    private ResponsibilityMessages() {
    }

    public record RequestRegionalResponsibility(
            String transferId,
            String regionId,
            String campaignId,
            long amountMicros,
            Instant requestedAt
    ) {
        public RequestRegionalResponsibility {
            transferId = requireNonBlank(transferId, "transferId");
            regionId = requireNonBlank(regionId, "regionId");
            campaignId = requireNonBlank(campaignId, "campaignId");
            requirePositive(amountMicros, "amountMicros");
            Objects.requireNonNull(requestedAt, "requestedAt");
        }
    }

    public record PreparedTransfer(
            String transferId,
            String regionId,
            String campaignId,
            long amountMicros,
            long writeGeneration
    ) {
        public PreparedTransfer {
            transferId = requireNonBlank(transferId, "transferId");
            regionId = requireNonBlank(regionId, "regionId");
            campaignId = requireNonBlank(campaignId, "campaignId");
            requirePositive(amountMicros, "amountMicros");
            requirePositive(writeGeneration, "writeGeneration");
        }
    }

    public record RegionalTransferActivation(
            String transferId,
            String regionId,
            String campaignId,
            long amountMicros,
            long writeGeneration
    ) {
        public RegionalTransferActivation {
            transferId = requireNonBlank(transferId, "transferId");
            regionId = requireNonBlank(regionId, "regionId");
            campaignId = requireNonBlank(campaignId, "campaignId");
            requirePositive(amountMicros, "amountMicros");
            requirePositive(writeGeneration, "writeGeneration");
        }
    }

    public sealed interface TransferPreparation permits TransferPrepared, TransferPreparationRejected {
    }

    public record TransferPrepared(PreparedTransfer transfer, boolean reused) implements TransferPreparation {
        public TransferPrepared {
            Objects.requireNonNull(transfer, "transfer");
        }
    }

    public record TransferPreparationRejected(TransferRejection reason) implements TransferPreparation {
        public TransferPreparationRejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public sealed interface TransferActivation permits TransferActivated, TransferActivationRejected {
    }

    public record TransferActivated(RegionalTransferActivation activation, boolean reused)
            implements TransferActivation {
        public TransferActivated {
            Objects.requireNonNull(activation, "activation");
        }
    }

    public record TransferActivationRejected(TransferRejection reason) implements TransferActivation {
        public TransferActivationRejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public sealed interface ResponsibilityTransferResult
            permits ResponsibilityTransferred, ResponsibilityTransferRejected {
    }

    public record ResponsibilityTransferred(String transferId, TransferOutcome outcome)
            implements ResponsibilityTransferResult {
        public ResponsibilityTransferred {
            transferId = requireNonBlank(transferId, "transferId");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    public record ResponsibilityTransferRejected(String transferId, TransferRejection reason)
            implements ResponsibilityTransferResult {
        public ResponsibilityTransferRejected {
            transferId = requireNonBlank(transferId, "transferId");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public enum TransferOutcome {
        COMPLETED,
        ALREADY_COMPLETED
    }

    public enum TransferRejection {
        GLOBAL_RESERVE_INSUFFICIENT,
        GLOBAL_LEDGER_UNAVAILABLE,
        REGIONAL_LEDGER_UNAVAILABLE,
        CONFLICT
    }

    public enum CompletionResult {
        COMPLETED,
        ALREADY_COMPLETED,
        REJECTED
    }
}
