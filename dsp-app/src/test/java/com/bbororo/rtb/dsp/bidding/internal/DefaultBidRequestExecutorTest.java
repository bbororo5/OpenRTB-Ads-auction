package com.bbororo.rtb.dsp.bidding.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.BidDecision;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.BidExecuted;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.BidRequestFingerprint;
import com.bbororo.rtb.dsp.contract.AuctionDeadline;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuthenticatedBidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Impression;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DefaultBidRequestExecutorTest {

    @Test
    void computesIdentityAndRunsCoordinationBehindTheExecutionGate() {
        var deadline = AuctionDeadline.start(50, () -> 0L);
        var request = new AuthenticatedBidRequest(
                "ssp-1",
                new BidRequest("request-1", 50, List.of(
                        new Impression("imp-1", 300, 250, 0, 2)
                )),
                Instant.EPOCH,
                deadline
        );
        var captured = new AtomicReference<com.bbororo.rtb.dsp.bidding.api.BiddingMessages.ExecuteBidOnce>();
        var expected = new BidDecision("request-1", List.of());
        var executor = new DefaultBidRequestExecutor(
                ignored -> new BidRequestFingerprint(1, "a".repeat(64)),
                (command, firstExecution) -> {
                    captured.set(command);
                    return new BidExecuted(firstExecution.get());
                },
                command -> {
                    assertSame(deadline, command.deadline());
                    return expected;
                }
        );

        var result = (BidExecuted) executor.execute(request);

        assertSame(expected, result.decision());
        assertEquals("ssp-1", captured.get().key().sspId());
        assertEquals("request-1", captured.get().key().requestId());
        assertSame(deadline, captured.get().command().deadline());
    }
}
