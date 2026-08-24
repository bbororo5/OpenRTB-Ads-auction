package com.bbororo.rtb.ssp.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bbororo.rtb.ssp.contract.AuctionDeadline;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.contract.SspMessages.BidResponses;
import com.bbororo.rtb.ssp.contract.SspMessages.DspBid;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcomeKind;
import com.bbororo.rtb.ssp.contract.SspMessages.NoticeKind;
import com.bbororo.rtb.ssp.contract.SspMessages.StartAuction;
import com.bbororo.rtb.ssp.dspbid.DspBidExecutor;
import com.bbororo.rtb.ssp.winner.FirstPriceWinnerSelector;
import com.bbororo.rtb.ssp.winner.WinnerSelector;
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
            return responses(new DspCallOutcome("external-dsp-1", DspCallOutcomeKind.VALID_BID,
                    List.of(bid("external-dsp-1", 2_000))));
        });

        var winners = coordinator.runAuction(start(AuctionDeadline.start(180, System::nanoTime)));

        assertEquals(1, calls.get());
        assertEquals(1, winners.winners().winners().size());
        assertEquals("external-dsp-1", winners.winners().winners().getFirst().dspId());
        assertEquals(NoticeKind.WIN, winners.notices().getFirst().kind());
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

        assertThrows(
                AuctionDeadlineExceededException.class,
                () -> coordinator.runAuction(start(deadline))
        );

        assertEquals(0, calls.get());
    }

    @Test
    void discardsResponsesThatArriveAfterTheDeadline() {
        AtomicLong nanos = new AtomicLong();
        AuctionDeadline deadline = AuctionDeadline.start(1, nanos::get);
        AuctionCoordinator coordinator = coordinator(batch -> {
            nanos.addAndGet(Duration.ofMillis(1).toNanos());
            return responses(new DspCallOutcome("external-dsp-1", DspCallOutcomeKind.VALID_BID,
                    List.of(bid("external-dsp-1", 2_000))));
        });

        assertThrows(
                AuctionDeadlineExceededException.class,
                () -> coordinator.runAuction(start(deadline))
        );
    }

    @Test
    void keepsAValidBidWhenOtherDspCallsFail() {
        AuctionCoordinator coordinator = coordinator(batch -> responses(
                new DspCallOutcome("project-dsp", DspCallOutcomeKind.TIMEOUT, List.of()),
                new DspCallOutcome("external-dsp-1", DspCallOutcomeKind.ERROR, List.of()),
                new DspCallOutcome(
                        "external-dsp-2",
                        DspCallOutcomeKind.VALID_BID,
                        List.of(bid("external-dsp-2", 2_000))
                )
        ));

        var outcome = coordinator.runAuction(
                start(AuctionDeadline.start(180, System::nanoTime))
        );

        assertEquals("external-dsp-2", outcome.winners().winners().getFirst().dspId());
        assertEquals(List.of(NoticeKind.WIN), outcome.notices().stream()
                .map(notice -> notice.kind())
                .toList());
    }

    @Test
    void rejectsAnOutcomeFromAnUnrequestedDsp() {
        AuctionCoordinator coordinator = coordinator(batch -> responses(
                new DspCallOutcome(
                        "unknown-dsp",
                        DspCallOutcomeKind.VALID_BID,
                        List.of(bid("unknown-dsp", 2_000))
                )
        ));

        assertThrows(
                IllegalStateException.class,
                () -> coordinator.runAuction(
                        start(AuctionDeadline.start(180, System::nanoTime))
                )
        );
    }

    @Test
    void failsWhenWinnerSelectionConsumesTheRemainingDeadline() {
        AtomicLong nanos = new AtomicLong();
        AuctionDeadline deadline = AuctionDeadline.start(1, nanos::get);
        WinnerSelector slowSelector = (auctionId, request, responses) -> {
            nanos.addAndGet(Duration.ofMillis(1).toNanos());
            return new com.bbororo.rtb.ssp.contract.SspMessages.AuctionWinners(List.of());
        };
        AuctionCoordinator coordinator = new DeadlineBoundAuctionCoordinator(
                batch -> responses(),
                slowSelector,
                List.of("project-dsp")
        );

        assertThrows(
                AuctionDeadlineExceededException.class,
                () -> coordinator.runAuction(start(deadline))
        );
    }

    @Test
    void rejectsDuplicateConfiguredDspParticipantsAtAssembly() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DeadlineBoundAuctionCoordinator(
                        ignored -> responses(),
                        new FirstPriceWinnerSelector(),
                        List.of("project-dsp", "project-dsp")
                )
        );
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
                "provider-1", "key-1", "request-1", 180,
                List.of(new AuctionSlot("imp-1", 300, 250, 0))
        ), deadline);
    }

    private static BidResponses responses(DspCallOutcome... outcomes) {
        return new BidResponses(List.of(outcomes));
    }

    private static DspBid bid(String dspId, long cpmMilliKrw) {
        URI callback = URI.create("https://" + dspId + ".example.test/notice");
        return new DspBid(dspId, "imp-1", "bid-1", cpmMilliKrw, callback, callback, callback);
    }
}
