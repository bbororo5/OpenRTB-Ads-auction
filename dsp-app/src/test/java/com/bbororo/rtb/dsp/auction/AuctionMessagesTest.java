package com.bbororo.rtb.dsp.auction;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bbororo.rtb.dsp.auction.AuctionMessages.BidDecision;
import com.bbororo.rtb.dsp.auction.AuctionMessages.CoordinateBid;
import com.bbororo.rtb.dsp.auction.AuctionMessages.PreparedBid;
import com.bbororo.rtb.dsp.auction.AuctionMessages.SlotAuctionKey;
import com.bbororo.rtb.dsp.contract.AuctionDeadline;
import com.bbororo.rtb.dsp.proof.NoticeIssuanceMessages.ReservationNoticeUrls;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuthenticatedBidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Impression;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuctionMessagesTest {

    @Test
    void slotAuctionIdentityUsesAuthenticatedSspRequestAndImpressionNamespaces() {
        var key = new SlotAuctionKey("ssp-1", "request-1", "imp-1");

        assertEquals(new SlotAuctionKey("ssp-1", "request-1", "imp-1"), key);
        assertNotEquals(new SlotAuctionKey("ssp-2", "request-1", "imp-1"), key);
        assertNotEquals(new SlotAuctionKey("ssp-1", "request-2", "imp-1"), key);
        assertNotEquals(new SlotAuctionKey("ssp-1", "request-1", "imp-2"), key);
    }

    @Test
    void slotAuctionIdentityRejectsBlankComponents() {
        assertThrows(IllegalArgumentException.class, () -> new SlotAuctionKey("", "request-1", "imp-1"));
        assertThrows(IllegalArgumentException.class, () -> new SlotAuctionKey("ssp-1", "", "imp-1"));
        assertThrows(IllegalArgumentException.class, () -> new SlotAuctionKey("ssp-1", "request-1", ""));
    }

    @Test
    void decisionAllowsAtMostOneBidPerImpression() {
        var urls = new ReservationNoticeUrls(
                URI.create("https://dsp.example/n/token"),
                URI.create("https://dsp.example/l/token"),
                URI.create("https://dsp.example/b/token")
        );
        var first = new PreparedBid("bid-1", "imp-1", "campaign-1", "creative-1", 1_000_000, urls);
        var second = new PreparedBid("bid-2", "imp-1", "campaign-2", "creative-2", 2_000_000, urls);

        assertThrows(IllegalArgumentException.class, () -> new BidDecision("auction-1", List.of(first, second)));
    }

    @Test
    void coordinateBidRequiresAnExplicitMonotonicDeadline() {
        Instant receivedAt = Instant.parse("2026-01-01T00:00:00Z");
        var request = new AuthenticatedBidRequest(
                "ssp-1",
                new BidRequest("auction-1", 50, List.of(new Impression("imp-1", 300, 250, 0, 2))),
                receivedAt
        );

        assertDoesNotThrow(() -> new CoordinateBid(request, AuctionDeadline.start(50, System::nanoTime)));
        assertThrows(NullPointerException.class, () -> new CoordinateBid(request, null));
    }
}
