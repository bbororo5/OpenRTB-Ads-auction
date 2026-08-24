package com.bbororo.rtb.ssp.contract;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.contract.SspMessages.BillingClaim;
import com.bbororo.rtb.ssp.contract.SspMessages.BidRequestBatch;
import com.bbororo.rtb.ssp.contract.SspMessages.DspBid;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcomeKind;
import com.bbororo.rtb.ssp.contract.SspMessages.ProofIssuance;
import com.bbororo.rtb.ssp.contract.SspMessages.WinningBid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuctionContractTest {

    @Test
    void rejectsANegativeSlotFloor() {
        assertThrows(IllegalArgumentException.class, () -> new AuctionSlot("imp-1", 300, 250, -1));
    }

    @Test
    void rejectsANonPositiveBannerDimension() {
        assertThrows(IllegalArgumentException.class, () -> new AuctionSlot("imp-1", 0, 250, 0));
        assertThrows(IllegalArgumentException.class, () -> new AuctionSlot("imp-1", 300, 0, 0));
    }

    @Test
    void rejectsDuplicateSlotIdentifiers() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest(
                        "provider-1", "key-1", "request-1", 180,
                        List.of(
                                new AuctionSlot("imp-1", 300, 250, 0),
                                new AuctionSlot("imp-1", 300, 250, 0)
                        )
                )
        );
    }

    @Test
    void rejectsAnIncompleteAuctionRequest() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuctionRequest(
                        "", "key-1", "request-1", 180,
                        List.of(new AuctionSlot("imp-1", 300, 250, 0))
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuctionRequest("provider-1", "key-1", "request-1", 180, List.of())
        );
    }

    @Test
    void rejectsDuplicateDspTargets() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BidRequestBatch(
                        "auction-1",
                        auction(),
                        List.of("dsp-1", "dsp-1"),
                        AuctionDeadline.start(180, () -> 0)
                )
        );
    }

    @Test
    void rejectsInvalidWinningAndBillingValues() {
        URI callback = URI.create("https://dsp.example.test/notice");
        assertThrows(
                IllegalArgumentException.class,
                () -> new WinningBid(
                        "auction-1/imp-1", "imp-1", "dsp-1", "bid-1", 0,
                        callback, callback, callback
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new BillingClaim(
                        "provider-1", "request-1", "imp-1", "auction-1/imp-1",
                        "not-a-sha-256-digest", "dsp-1", 1_000, callback, Instant.now()
                )
        );
    }

    @Test
    void requiresProofExpiryAfterIssuance() {
        Instant issuedAt = Instant.parse("2026-07-29T00:00:00Z");
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProofIssuance(auction(), "auction-1", winningBid(), issuedAt, issuedAt)
        );
    }

    @Test
    void requiresAProofWinnerToBelongToTheAuctionSlot() {
        Instant issuedAt = Instant.parse("2026-07-29T00:00:00Z");
        URI callback = URI.create("https://dsp.example.test/notice");
        WinningBid unknownSlot = new WinningBid(
                "auction-1/imp-2", "imp-2", "dsp-1", "bid-1", 1_000,
                callback, callback, callback
        );
        WinningBid wrongAuction = new WinningBid(
                "another-auction/imp-1", "imp-1", "dsp-1", "bid-1", 1_000,
                callback, callback, callback
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProofIssuance(
                        auction(), "auction-1", unknownSlot, issuedAt, issuedAt.plusSeconds(2)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProofIssuance(
                        auction(), "auction-1", wrongAuction, issuedAt, issuedAt.plusSeconds(2)
                )
        );
    }

    @Test
    void refusesToSealAWinnerBelowTheRequestedSlotFloor() {
        Instant issuedAt = Instant.parse("2026-07-29T00:00:00Z");
        AuctionRequest request = new AuctionRequest(
                "provider-1", "key-1", "request-1", 180,
                List.of(new AuctionSlot("imp-1", 300, 250, 2_000))
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProofIssuance(
                        request, "auction-1", winningBid(), issuedAt, issuedAt.plusSeconds(2)
                )
        );
    }

    @Test
    void requiresBidsOnlyForAValidBidOutcome() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DspCallOutcome("dsp-1", DspCallOutcomeKind.VALID_BID, List.of())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new DspCallOutcome("dsp-1", DspCallOutcomeKind.NO_BID, List.of(bid()))
        );
    }

    @Test
    void requiresEveryBidToBelongToItsDspOutcome() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DspCallOutcome("dsp-1", DspCallOutcomeKind.VALID_BID, List.of(bid("dsp-2")))
        );
    }

    @Test
    void rejectsRepeatedBidIdentifiersWithinOneDspOutcome() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DspCallOutcome(
                        "dsp-1",
                        DspCallOutcomeKind.VALID_BID,
                        List.of(bid(), bid())
                )
        );
    }

    private static DspBid bid() {
        return bid("dsp-1");
    }

    private static DspBid bid(String dspId) {
        URI callback = URI.create("https://dsp.example.test/notice");
        return new DspBid(dspId, "imp-1", "bid-1", 1_000, callback, callback, callback);
    }

    private static AuctionRequest auction() {
        return new AuctionRequest(
                "provider-1", "key-1", "request-1", 180,
                List.of(new AuctionSlot("imp-1", 300, 250, 0))
        );
    }

    private static WinningBid winningBid() {
        URI callback = URI.create("https://dsp.example.test/notice");
        return new WinningBid(
                "auction-1/imp-1", "imp-1", "dsp-1", "bid-1", 1_000,
                callback, callback, callback
        );
    }
}
