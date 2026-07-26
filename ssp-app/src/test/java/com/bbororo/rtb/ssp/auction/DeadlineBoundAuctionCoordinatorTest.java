package com.bbororo.rtb.ssp.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bbororo.rtb.ssp.contract.AuctionDeadline;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.contract.SspMessages.BidResponses;
import com.bbororo.rtb.ssp.contract.SspMessages.DspBid;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcomeKind;
import com.bbororo.rtb.ssp.contract.SspMessages.StartAuction;
import com.bbororo.rtb.ssp.dspbid.DspBidExecutor;
import com.bbororo.rtb.ssp.winner.FirstPriceWinnerSelector;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DeadlineBoundAuctionCoordinatorTest {

    @Test
    void selectsFromAvailableDspOutcomesBeforeTheDeadline() {
        AtomicInteger calls = new AtomicInteger();
        AuctionCoordinator coordinator = coordinator(batch -> {
            calls.incrementAndGet();
            return responses(new DspCallOutcome("external-dsp", DspCallOutcomeKind.VALID_BID,
                    List.of(bid("external-dsp", 2_000))));
        });

        var winners = coordinator.runAuction(start(AuctionDeadline.start(180, System::nanoTime)));

        assertEquals(1, calls.get());
        assertEquals(1, winners.winners().size());
        assertEquals("external-dsp", winners.winners().getFirst().dspId());
    }

    @Test
    void doesNotCallDspWhenTheDeadlineAlreadyExpired() {
        AtomicLong nanos = new AtomicLong();
        AuctionDeadline deadline = AuctionDeadline.start(1, nanos::get);
        nanos.addAndGet(Duration.ofMillis(1).toNanos());
        AtomicInteger calls = new AtomicInteger();
        AuctionCoordinator coordinator = coordinator(batch -> {
            calls.incrementAndGet();
            return responses();
        });

        var winners = coordinator.runAuction(start(deadline));

        assertEquals(0, calls.get());
        assertEquals(List.of(), winners.winners());
    }

    @Test
    void discardsResponsesThatArriveAfterTheDeadline() {
        AtomicLong nanos = new AtomicLong();
        AuctionDeadline deadline = AuctionDeadline.start(1, nanos::get);
        AuctionCoordinator coordinator = coordinator(batch -> {
            nanos.addAndGet(Duration.ofMillis(1).toNanos());
            return responses(new DspCallOutcome("external-dsp", DspCallOutcomeKind.VALID_BID,
                    List.of(bid("external-dsp", 2_000))));
        });

        var winners = coordinator.runAuction(start(deadline));

        assertEquals(List.of(), winners.winners());
    }

    private static AuctionCoordinator coordinator(DspBidExecutor executor) {
        return new DeadlineBoundAuctionCoordinator(
                executor,
                new FirstPriceWinnerSelector(),
                List.of("project-dsp", "external-dsp-1", "external-dsp-2")
        );
    }

    private static StartAuction start(AuctionDeadline deadline) {
        return new StartAuction(new AuctionRequest(
                "provider-1", "key-1", "request-1", 180, List.of(new AuctionSlot("imp-1", 0))
        ), deadline);
    }

    private static BidResponses responses(DspCallOutcome... outcomes) {
        return new BidResponses(List.of(outcomes));
    }

    private static DspBid bid(String dspId, long cpmKrw) {
        URI callback = URI.create("https://" + dspId + ".example.test/notice");
        return new DspBid(dspId, "imp-1", "bid-1", cpmKrw, callback, callback, callback);
    }
}
