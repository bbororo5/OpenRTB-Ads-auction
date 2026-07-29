package com.bbororo.rtb.ssp.winner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionWinners;
import com.bbororo.rtb.ssp.contract.SspMessages.BidResponses;
import com.bbororo.rtb.ssp.contract.SspMessages.DspBid;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcomeKind;
import com.bbororo.rtb.ssp.contract.SspMessages.WinningBid;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FirstPriceWinnerSelectorTest {

    private final WinnerSelector selector = new FirstPriceWinnerSelector();

    @Test
    void selectsTheHighestValidBidForEachSlotAtTheSubmittedPrice() {
        AuctionWinners winners = selector.selectWinners(
                "auction-1",
                auction(),
                responses(
                        outcome("project-dsp", bid("project-dsp", "imp-1", "bid-1", 1_200), bid("project-dsp", "imp-2", "bid-2", 500)),
                        outcome("external-dsp-1", bid("external-dsp-1", "imp-1", "bid-3", 1_500), bid("external-dsp-1", "imp-2", "bid-4", 600)),
                        new DspCallOutcome("external-dsp-2", DspCallOutcomeKind.NO_BID, List.of())
                )
        );

        assertEquals(List.of("external-dsp-1", "external-dsp-1"), winners.winners().stream().map(WinningBid::dspId).toList());
        assertEquals(List.of(1_500L, 600L), winners.winners().stream().map(WinningBid::cpmMilliKrw).toList());
        assertEquals(List.of("auction-1/imp-1", "auction-1/imp-2"), winners.winners().stream().map(WinningBid::slotAuctionKey).toList());
    }

    @Test
    void excludesUnknownSlotsAndBidsBelowTheFloor() {
        AuctionWinners winners = selector.selectWinners(
                "auction-1",
                auction(),
                responses(outcome(
                        "project-dsp",
                        bid("project-dsp", "unknown", "bid-1", 9_999),
                        bid("project-dsp", "imp-1", "bid-2", 999)
                ))
        );

        assertTrue(winners.winners().isEmpty());
    }

    @Test
    void acceptsABidExactlyAtTheSlotFloor() {
        AuctionWinners winners = selector.selectWinners(
                "auction-1",
                auction(),
                responses(outcome("dsp-a", bid("dsp-a", "imp-1", "bid-1", 1_000)))
        );

        assertEquals(1_000L, winners.winners().getFirst().cpmMilliKrw());
    }

    @Test
    void preservesAOneMilliKrwCpmPriceDifference() {
        WinningBid winner = selector.selectWinners(
                "auction-1",
                auction(),
                responses(
                        outcome("dsp-a", bid("dsp-a", "imp-1", "bid-a", 2_000_000)),
                        outcome("dsp-b", bid("dsp-b", "imp-1", "bid-b", 2_000_001))
                )
        ).winners().getFirst();

        assertEquals("dsp-b", winner.dspId());
        assertEquals(2_000_001L, winner.cpmMilliKrw());
    }

    @Test
    void makesEqualPriceResultsIndependentOfResponseOrder() {
        DspCallOutcome dspA = outcome("dsp-a", bid("dsp-a", "imp-1", "bid-a", 2_000));
        DspCallOutcome dspB = outcome("dsp-b", bid("dsp-b", "imp-1", "bid-b", 2_000));

        WinningBid first = selector.selectWinners(
                "auction-1", auction(), responses(dspA, dspB)
        ).winners().getFirst();
        WinningBid reversed = selector.selectWinners(
                "auction-1", auction(), responses(dspB, dspA)
        ).winners().getFirst();

        assertEquals(first.dspId(), reversed.dspId());
        assertEquals(first.bidId(), reversed.bidId());
    }

    @Test
    void distributesEqualPriceWinsInsteadOfAlwaysFavoringOneDsp() {
        DspCallOutcome dspA = outcome("dsp-a", bid("dsp-a", "imp-1", "bid-a", 2_000));
        DspCallOutcome dspB = outcome("dsp-b", bid("dsp-b", "imp-1", "bid-b", 2_000));

        Set<String> winners = IntStream.range(0, 100)
                .mapToObj(index -> selector.selectWinners(
                        "auction-" + index,
                        auction(),
                        responses(dspA, dspB)
                ).winners().getFirst().dspId())
                .collect(Collectors.toSet());

        assertEquals(Set.of("dsp-a", "dsp-b"), winners);
    }

    @Test
    void rejectsABlankAuctionIdentifier() {
        assertThrows(
                IllegalArgumentException.class,
                () -> selector.selectWinners(" ", auction(), responses(
                        outcome("dsp-a", bid("dsp-a", "imp-1", "bid-1", 2_000))
                ))
        );
    }

    @Test
    void ignoresTimedOutAndFailedDspOutcomesWithoutDiscardingOtherBids() {
        AuctionWinners winners = selector.selectWinners(
                "auction-1",
                auction(),
                responses(
                        new DspCallOutcome("external-dsp-1", DspCallOutcomeKind.TIMEOUT, List.of()),
                        new DspCallOutcome("external-dsp-2", DspCallOutcomeKind.ERROR, List.of()),
                        outcome("project-dsp", bid("project-dsp", "imp-1", "bid-1", 1_200))
                )
        );

        assertEquals(List.of("project-dsp"), winners.winners().stream().map(WinningBid::dspId).toList());
    }

    private static AuctionRequest auction() {
        return new AuctionRequest(
                "provider-1",
                "key-1",
                "request-1",
                180,
                List.of(new AuctionSlot("imp-1", 1_000), new AuctionSlot("imp-2", 0))
        );
    }

    private static BidResponses responses(DspCallOutcome... outcomes) {
        return new BidResponses(List.of(outcomes));
    }

    private static DspCallOutcome outcome(String dspId, DspBid... bids) {
        return new DspCallOutcome(dspId, DspCallOutcomeKind.VALID_BID, List.of(bids));
    }

    private static DspBid bid(String dspId, String impId, String bidId, long cpmMilliKrw) {
        URI callback = URI.create("https://" + dspId + ".example.test/notice");
        return new DspBid(dspId, impId, bidId, cpmMilliKrw, callback, callback, callback);
    }
}
