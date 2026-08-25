package com.bbororo.rtb.dsp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.BidDecision;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.BidExecuted;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.BidExecutionRejected;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.BidExecutionRejection;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.PreparedBid;
import com.bbororo.rtb.dsp.contract.AuctionDeadline;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuctionNotice;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuthenticatedBidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidResponse;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Impression;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoContent;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoticeHttpResult;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoticeKind;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.NoticeProcessed;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.NoticeProcessingStatus;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.ReservationNoticeUrls;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DefaultDspOpenRtbApiTest {

    @Test
    void mapsPreparedBidsWithoutChangingTheirEconomicMeaning() {
        var prepared = new PreparedBid(
                "bid-1", "imp-1", "campaign-1", "creative-1", 2_000,
                new ReservationNoticeUrls(
                        URI.create("https://dsp.test/nurl/token"),
                        URI.create("https://dsp.test/lurl/token"),
                        URI.create("https://dsp.test/burl/token")
                )
        );
        var api = new DefaultDspOpenRtbApi(
                ignored -> new BidExecuted(new BidDecision("request-1", List.of(prepared))),
                ignored -> CompletableFuture.failedFuture(new AssertionError("not called"))
        );

        var response = (BidResponse) api.handleBid(request());
        var bid = response.seatbid().getFirst().bids().getFirst();

        assertEquals("request-1", response.id());
        assertEquals("imp-1", bid.impressionId());
        assertEquals(2_000, bid.cpmMilliKrw());
        assertEquals(prepared.notificationUrls().billingNoticeUrl(), bid.billingUrl());
    }

    @Test
    void separatesReasonlessNoBidFromRejectedExecution() {
        var noCandidate = new DefaultDspOpenRtbApi(
                ignored -> new BidExecuted(new BidDecision("request-1", List.of())),
                ignored -> CompletableFuture.failedFuture(new AssertionError("not called"))
        );
        var duplicate = new DefaultDspOpenRtbApi(
                ignored -> new BidExecutionRejected(BidExecutionRejection.DUPLICATE_REQUEST),
                ignored -> CompletableFuture.failedFuture(new AssertionError("not called"))
        );

        assertSame(NoContent.INSTANCE, noCandidate.handleBid(request()));
        assertEquals(2, ((BidResponse) duplicate.handleBid(request())).nbr().orElseThrow());
    }

    @Test
    void mapsAuctionNoticeToTheOutcomeContract() {
        var captured = new AtomicReference<com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.ProcessReservationNotice>();
        var api = new DefaultDspOpenRtbApi(
                ignored -> new BidExecuted(new BidDecision("request-1", List.of())),
                notice -> {
                    captured.set(notice);
                    return CompletableFuture.completedFuture(new NoticeProcessed(
                            NoticeProcessingStatus.DUPLICATE, "reservation-1"));
                }
        );
        var receivedAt = Instant.parse("2026-08-25T00:00:00Z");

        var result = api.handleNotice(new AuctionNotice(
                "ssp-1", NoticeKind.BILLING, "opaque", receivedAt)).toCompletableFuture().join();

        assertEquals(NoticeHttpResult.ACCEPTED, result);
        assertEquals("ssp-1", captured.get().authenticatedSspId());
        assertEquals(com.bbororo.rtb.dsp.proof.api.ReservationNoticeKind.BILLING,
                captured.get().kind());
        assertEquals(receivedAt, captured.get().receivedAt());
    }

    private static AuthenticatedBidRequest request() {
        return new AuthenticatedBidRequest(
                "ssp-1",
                new BidRequest("request-1", 50, List.of(
                        new Impression("imp-1", 300, 250, 0, 2)
                )),
                Instant.EPOCH,
                AuctionDeadline.start(50, () -> 0L)
        );
    }
}
