package com.bbororo.rtb.dsp.responsibility.internal;

import static com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.CompletionResult.ALREADY_COMPLETED;
import static com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.CompletionResult.COMPLETED;
import static com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.TransferRejection.CONFLICT;
import static com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.TransferRejection.GLOBAL_RESERVE_INSUFFICIENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityTransfer;
import com.bbororo.rtb.dsp.responsibility.spi.GlobalResponsibilityLedger;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.PreparedTransfer;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.RegionalTransferActivation;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.RequestRegionalResponsibility;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.ResponsibilityTransferRejected;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.ResponsibilityTransferred;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.TransferActivated;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.TransferPreparationRejected;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.TransferPrepared;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class DefaultResponsibilityTransferTest {

    @Test
    void preparesActivatesAndCompletesInOrder() {
        var fixture = new Fixture();

        var result = fixture.transfer.request(request()).toCompletableFuture().join();

        var transferred = assertInstanceOf(ResponsibilityTransferred.class, result);
        assertEquals(ResponsibilityMessages.TransferOutcome.COMPLETED, transferred.outcome());
        assertEquals(List.of("prepare", "activate", "complete"), fixture.calls);
    }

    @Test
    void stopsWhenGlobalPreparationIsRejected() {
        var fixture = new Fixture();
        fixture.preparation = new TransferPreparationRejected(GLOBAL_RESERVE_INSUFFICIENT);

        var result = fixture.transfer.request(request()).toCompletableFuture().join();

        var rejected = assertInstanceOf(ResponsibilityTransferRejected.class, result);
        assertEquals(GLOBAL_RESERVE_INSUFFICIENT, rejected.reason());
        assertEquals(List.of("prepare"), fixture.calls);
    }

    @Test
    void rejectsARegionalActivationThatDoesNotMatchThePreparedTransfer() {
        var fixture = new Fixture();
        fixture.returnMismatchedActivation = true;

        var result = fixture.transfer.request(request()).toCompletableFuture().join();

        var rejected = assertInstanceOf(ResponsibilityTransferRejected.class, result);
        assertEquals(CONFLICT, rejected.reason());
        assertEquals(List.of("prepare", "activate"), fixture.calls);
    }

    @Test
    void reportsAlreadyCompletedWithoutRepeatingMoneyEffects() {
        var fixture = new Fixture();
        fixture.completion = ALREADY_COMPLETED;

        var result = fixture.transfer.request(request()).toCompletableFuture().join();

        var transferred = assertInstanceOf(ResponsibilityTransferred.class, result);
        assertEquals(
                ResponsibilityMessages.TransferOutcome.ALREADY_COMPLETED,
                transferred.outcome()
        );
    }

    private static RequestRegionalResponsibility request() {
        return new RequestRegionalResponsibility(
                "transfer-1", "region-1", "campaign-1", 1_000,
                Instant.parse("2026-01-01T00:00:00Z")
        );
    }

    private static final class Fixture {
        private final List<String> calls = new ArrayList<>();
        private ResponsibilityMessages.TransferPreparation preparation = new TransferPrepared(
                new PreparedTransfer(
                        "transfer-1", "region-1", "campaign-1", 1_000, 7
                ),
                false
        );
        private ResponsibilityMessages.CompletionResult completion = COMPLETED;
        private boolean returnMismatchedActivation;
        private final ResponsibilityTransfer transfer = new DefaultResponsibilityTransfer(
                new GlobalResponsibilityLedger() {
                    @Override
                    public java.util.concurrent.CompletionStage<ResponsibilityMessages.TransferPreparation> prepare(
                            RequestRegionalResponsibility command
                    ) {
                        calls.add("prepare");
                        return CompletableFuture.completedFuture(preparation);
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<ResponsibilityMessages.CompletionResult> complete(
                            RegionalTransferActivation activation
                    ) {
                        calls.add("complete");
                        return CompletableFuture.completedFuture(completion);
                    }
                },
                activation -> {
                    calls.add("activate");
                    var result = returnMismatchedActivation
                            ? new RegionalTransferActivation(
                                    activation.transferId(), activation.regionId(),
                                    activation.campaignId(), activation.amountMicros() + 1,
                                    activation.writeGeneration()
                            )
                            : activation;
                    return CompletableFuture.completedFuture(new TransferActivated(result, false));
                }
        );
    }
}
