package com.bbororo.rtb.dsp.openrtb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Bid;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidResponse;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Impression;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoContent;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.SeatBid;
import java.net.URI;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class OpenRtbMessagesTest {

    @Test
    void bidResponseCarriesSeatBidsForHttp200() {
        var response = BidResponse.withBids("auction-1", List.of(
                new SeatBid(List.of(bid()))
        ));

        assertEquals("auction-1", response.id());
        assertEquals(1, response.seatbid().size());
        assertEquals(OptionalInt.empty(), response.nbr());
    }

    @Test
    void bidResponseCarriesNbrForReasonedNoBid() {
        var response = BidResponse.noBid("auction-1", 2);

        assertEquals(List.of(), response.seatbid());
        assertEquals(OptionalInt.of(2), response.nbr());
    }

    @Test
    void noContentIsAnAllocationFreeHttp204Marker() {
        assertSame(NoContent.INSTANCE, NoContent.INSTANCE);
    }

    @Test
    void bidResponseRejectsAnEmptyReasonlessHttp200Body() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BidResponse("auction-1", List.of(), OptionalInt.empty())
        );
    }

    @Test
    void bidResponseRejectsBidsAndNbrTogether() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BidResponse(
                        "auction-1",
                        List.of(new SeatBid(List.of(bid()))),
                        OptionalInt.of(2)
                )
        );
    }

    @Test
    void seatBidRequiresAtLeastOneBid() {
        assertThrows(IllegalArgumentException.class, () -> new SeatBid(List.of()));
    }

    @Test
    void requestRejectsDuplicateImpressionIds() {
        var impression = new Impression("imp-1", 300, 250, 1_000_000, 2);

        assertThrows(
                IllegalArgumentException.class,
                () -> new BidRequest("auction-1", 50, List.of(impression, impression))
        );
    }

    @Test
    void requestRejectsDeadlineBeyondProjectContract() {
        var impression = new Impression("imp-1", 300, 250, 1_000_000, 2);

        assertThrows(
                IllegalArgumentException.class,
                () -> new BidRequest("auction-1", 181, List.of(impression))
        );
    }

    @Test
    void impressionRequiresTheAgreedTwoSecondRenderWindow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Impression("imp-1", 300, 250, 1_000_000, 3)
        );
    }

    private static Bid bid() {
        return new Bid(
                "bid-1",
                "imp-1",
                "campaign-1",
                "creative-1",
                1_000,
                URI.create("https://dsp.example/notices/win"),
                URI.create("https://dsp.example/notices/loss"),
                URI.create("https://dsp.example/notices/billing"),
                2
        );
    }
}
