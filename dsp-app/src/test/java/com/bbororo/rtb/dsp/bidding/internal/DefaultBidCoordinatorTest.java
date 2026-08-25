package com.bbororo.rtb.dsp.bidding.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.CoordinateBid;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.PreparedBid;
import com.bbororo.rtb.dsp.contract.AuctionDeadline;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuthenticatedBidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Impression;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.ReservationNoticeUrls;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultBidCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");

    @Test
    void preservesSlotPartialSuccessAndStopsAfterRequestHalt() {
        var calls = new AtomicInteger();
        SlotBidWorkflow workflow = command -> switch (command.impression().id()) {
            case "imp-1" -> new SlotBidWorkflow.Prepared(prepared("bid-1", "imp-1"));
            case "imp-2" -> new SlotBidWorkflow.NoBid(
                    SlotBidWorkflow.NoBidReason.CANDIDATES_EXHAUSTED);
            case "imp-3" -> {
                calls.incrementAndGet();
                yield new SlotBidWorkflow.HaltRequest(
                        SlotBidWorkflow.RequestHaltReason.LOCAL_CAPACITY_EXHAUSTED);
            }
            default -> throw new AssertionError("slot after halt must not run");
        };
        var coordinator = new DefaultBidCoordinator(workflow, timePolicy(true, true));

        var decision = coordinator.coordinate(command("imp-1", "imp-2", "imp-3", "imp-4"));

        assertEquals(List.of("imp-1"), decision.bids().stream()
                .map(PreparedBid::impressionId)
                .toList());
        assertEquals(1, calls.get());
    }

    @Test
    void dropsPreparedBidsWhenPublicationReserveHasBeenConsumed() {
        SlotBidWorkflow workflow = command ->
                new SlotBidWorkflow.Prepared(prepared("bid-1", command.impression().id()));
        var coordinator = new DefaultBidCoordinator(workflow, timePolicy(true, false));

        var decision = coordinator.coordinate(command("imp-1"));

        assertEquals(List.of(), decision.bids());
    }

    @Test
    void startsNoSlotWhenWorkBudgetIsAlreadyExhausted() {
        SlotBidWorkflow workflow = command -> {
            throw new AssertionError("slot must not start");
        };
        var coordinator = new DefaultBidCoordinator(workflow, timePolicy(false, false));

        var decision = coordinator.coordinate(command("imp-1"));

        assertEquals(List.of(), decision.bids());
    }

    private static CoordinateBid command(String... impressionIds) {
        var impressions = java.util.Arrays.stream(impressionIds)
                .map(id -> new Impression(id, 300, 250, 500, 2))
                .toList();
        var request = new BidRequest("auction-1", 50, impressions);
        return new CoordinateBid(
                new AuthenticatedBidRequest("ssp-1", request, NOW),
                AuctionDeadline.start(50, System::nanoTime)
        );
    }

    private static PreparedBid prepared(String bidId, String impressionId) {
        return new PreparedBid(
                bidId,
                impressionId,
                "campaign-1",
                "creative-1",
                1_000,
                new ReservationNoticeUrls(
                        URI.create("https://dsp.example/n/token"),
                        URI.create("https://dsp.example/l/token"),
                        URI.create("https://dsp.example/b/token")
                )
        );
    }

    private static BidTimePolicy timePolicy(boolean canWork, boolean canPublish) {
        return new BidTimePolicy() {
            @Override
            public boolean canStartSlot(AuctionDeadline deadline) {
                return canWork;
            }

            @Override
            public boolean canStartCandidate(AuctionDeadline deadline) {
                return canWork;
            }

            @Override
            public boolean canPublish(AuctionDeadline deadline) {
                return canPublish;
            }
        };
    }
}
