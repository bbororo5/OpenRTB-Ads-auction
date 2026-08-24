package com.bbororo.rtb.dsp.responsibility.internal;

import static com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.CompletionResult.ALREADY_COMPLETED;
import static com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.CompletionResult.COMPLETED;
import static com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.TransferRejection.CONFLICT;
import static com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.TransferRejection.GLOBAL_LEDGER_UNAVAILABLE;
import static com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.TransferRejection.REGIONAL_LEDGER_UNAVAILABLE;

import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityTransfer;
import com.bbororo.rtb.dsp.responsibility.spi.GlobalResponsibilityLedger;
import com.bbororo.rtb.dsp.responsibility.spi.RegionalResponsibilityLedger;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.PreparedTransfer;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.RegionalTransferActivation;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.RequestRegionalResponsibility;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.ResponsibilityTransferRejected;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.ResponsibilityTransferResult;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.ResponsibilityTransferred;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.TransferActivated;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.TransferActivationRejected;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.TransferPrepared;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.TransferPreparationRejected;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 전역 격리, 지역 활성화, 전역 완료를 같은 transferId로 순서대로 조정한다. */
public final class DefaultResponsibilityTransfer implements ResponsibilityTransfer {

    private final GlobalResponsibilityLedger globalLedger;
    private final RegionalResponsibilityLedger regionalLedger;

    public DefaultResponsibilityTransfer(
            GlobalResponsibilityLedger globalLedger,
            RegionalResponsibilityLedger regionalLedger
    ) {
        this.globalLedger = Objects.requireNonNull(globalLedger, "globalLedger");
        this.regionalLedger = Objects.requireNonNull(regionalLedger, "regionalLedger");
    }

    @Override
    public CompletionStage<ResponsibilityTransferResult> request(
            RequestRegionalResponsibility command
    ) {
        Objects.requireNonNull(command, "command");
        return prepare(command).thenCompose(preparation -> {
            if (preparation instanceof TransferPreparationRejected rejected) {
                return completed(new ResponsibilityTransferRejected(
                        command.transferId(), rejected.reason()
                ));
            }
            PreparedTransfer prepared = ((TransferPrepared) preparation).transfer();
            if (!matches(command, prepared)) {
                return completed(rejected(command.transferId(), CONFLICT));
            }
            return activate(prepared).thenCompose(activation -> {
                if (activation instanceof TransferActivationRejected rejected) {
                    return completed(new ResponsibilityTransferRejected(
                            command.transferId(), rejected.reason()
                    ));
                }
                RegionalTransferActivation activated = ((TransferActivated) activation).activation();
                if (!matches(prepared, activated)) {
                    return completed(rejected(command.transferId(), CONFLICT));
                }
                return complete(activated).thenApply(result -> switch (result) {
                    case COMPLETED -> new ResponsibilityTransferred(
                            command.transferId(),
                            ResponsibilityMessages.TransferOutcome.COMPLETED
                    );
                    case ALREADY_COMPLETED -> new ResponsibilityTransferred(
                            command.transferId(),
                            ResponsibilityMessages.TransferOutcome.ALREADY_COMPLETED
                    );
                    case REJECTED -> rejected(command.transferId(), CONFLICT);
                });
            });
        });
    }

    private CompletionStage<ResponsibilityMessages.TransferPreparation> prepare(
            RequestRegionalResponsibility command
    ) {
        try {
            return globalLedger.prepare(command).exceptionally(failure ->
                    new TransferPreparationRejected(GLOBAL_LEDGER_UNAVAILABLE)
            );
        } catch (RuntimeException failure) {
            return completed(new TransferPreparationRejected(GLOBAL_LEDGER_UNAVAILABLE));
        }
    }

    private CompletionStage<ResponsibilityMessages.TransferActivation> activate(
            PreparedTransfer prepared
    ) {
        var activation = new RegionalTransferActivation(
                prepared.transferId(),
                prepared.regionId(),
                prepared.campaignId(),
                prepared.amountMicros(),
                prepared.writeGeneration()
        );
        try {
            return regionalLedger.activate(activation).exceptionally(failure ->
                    new TransferActivationRejected(REGIONAL_LEDGER_UNAVAILABLE)
            );
        } catch (RuntimeException failure) {
            return completed(new TransferActivationRejected(REGIONAL_LEDGER_UNAVAILABLE));
        }
    }

    private CompletionStage<ResponsibilityMessages.CompletionResult> complete(
            RegionalTransferActivation activation
    ) {
        try {
            return globalLedger.complete(activation).exceptionally(failure ->
                    ResponsibilityMessages.CompletionResult.REJECTED
            );
        } catch (RuntimeException failure) {
            return completed(ResponsibilityMessages.CompletionResult.REJECTED);
        }
    }

    private static boolean matches(
            RequestRegionalResponsibility request,
            PreparedTransfer prepared
    ) {
        return request.transferId().equals(prepared.transferId())
                && request.regionId().equals(prepared.regionId())
                && request.campaignId().equals(prepared.campaignId())
                && request.amountMicros() == prepared.amountMicros();
    }

    private static boolean matches(
            PreparedTransfer prepared,
            RegionalTransferActivation activated
    ) {
        return prepared.transferId().equals(activated.transferId())
                && prepared.regionId().equals(activated.regionId())
                && prepared.campaignId().equals(activated.campaignId())
                && prepared.amountMicros() == activated.amountMicros()
                && prepared.writeGeneration() == activated.writeGeneration();
    }

    private static ResponsibilityTransferRejected rejected(
            String transferId,
            ResponsibilityMessages.TransferRejection reason
    ) {
        return new ResponsibilityTransferRejected(transferId, reason);
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
