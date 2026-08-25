package com.bbororo.rtb.dsp.bidding.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.CoordinateBid;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.PreparedBid;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.CampaignCandidate;
import com.bbororo.rtb.dsp.contract.AuctionDeadline;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuthenticatedBidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Impression;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.ReservationNoticeUrls;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationGranted;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationRejection;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultSlotBidWorkflowTest {

    private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");
    private static final Impression IMPRESSION = new Impression("imp-1", 300, 250, 500, 2);
    private static final CampaignCandidate FIRST =
            new CampaignCandidate("campaign-1", "creative-1", 1_000, 20);
    private static final CampaignCandidate SECOND =
            new CampaignCandidate("campaign-2", "creative-2", 2_000, 10);

    @Test
    void retriesTheNextCandidateAfterCampaignLocalContention() {
        var attempts = new AtomicInteger();
        CandidateBidAttempt candidateAttempt = command -> {
            if (attempts.getAndIncrement() == 0) {
                return new CandidateBidAttempt.AttemptRejected(ReservationRejection.CONTENDED);
            }
            return new CandidateBidAttempt.AttemptPrepared(preparedBid("bid-2"));
        };
        var workflow = workflow(List.of(FIRST, SECOND), candidateAttempt, alwaysHasTime());

        var prepared = assertInstanceOf(
                SlotBidWorkflow.Prepared.class,
                workflow.prepare(slotCommand())
        );

        assertEquals("bid-2", prepared.bid().bidId());
        assertEquals(2, attempts.get());
    }

    @Test
    void haltsTheRequestWithoutTryingMoreCandidatesWhenInstanceIsFull() {
        var attempts = new AtomicInteger();
        CandidateBidAttempt candidateAttempt = command -> {
            attempts.incrementAndGet();
            return new CandidateBidAttempt.AttemptRejected(
                    ReservationRejection.INSTANCE_CAPACITY_EXCEEDED);
        };
        var workflow = workflow(List.of(FIRST, SECOND), candidateAttempt, alwaysHasTime());

        var halted = assertInstanceOf(
                SlotBidWorkflow.HaltRequest.class,
                workflow.prepare(slotCommand())
        );

        assertEquals(SlotBidWorkflow.RequestHaltReason.LOCAL_CAPACITY_EXHAUSTED,
                halted.reason());
        assertEquals(1, attempts.get());
    }

    @Test
    void haltsAfterAReservationBecomesAbandoned() {
        CandidateBidAttempt candidateAttempt = command ->
                new CandidateBidAttempt.AttemptAbandoned(
                        granted(),
                        CandidateBidAttempt.AbandonmentReason.NOTICE_ISSUANCE_FAILED
                );
        var workflow = workflow(List.of(FIRST, SECOND), candidateAttempt, alwaysHasTime());

        var halted = assertInstanceOf(
                SlotBidWorkflow.HaltRequest.class,
                workflow.prepare(slotCommand())
        );

        assertEquals(SlotBidWorkflow.RequestHaltReason.RESERVATION_ABANDONED,
                halted.reason());
    }

    @Test
    void returnsNoBidWithoutAttemptWhenThereAreNoEligibleCandidates() {
        var workflow = workflow(
                List.of(),
                command -> {
                    throw new AssertionError("candidate must not be attempted");
                },
                alwaysHasTime()
        );

        var noBid = assertInstanceOf(
                SlotBidWorkflow.NoBid.class,
                workflow.prepare(slotCommand())
        );

        assertEquals(SlotBidWorkflow.NoBidReason.NO_ELIGIBLE_CANDIDATE,
                noBid.reason());
    }

    @Test
    void doesNotQueryCandidatesWithoutEnoughTimeToStartTheSlot() {
        var workflow = new DefaultSlotBidWorkflow(
                request -> {
                    throw new AssertionError("candidates must not be queried");
                },
                command -> {
                    throw new AssertionError("candidate must not be attempted");
                },
                neverHasTime(),
                new DefaultCandidateContinuationPolicy(),
                fixedClock()
        );

        var noBid = assertInstanceOf(
                SlotBidWorkflow.NoBid.class,
                workflow.prepare(slotCommand())
        );

        assertEquals(SlotBidWorkflow.NoBidReason.DEADLINE_BUDGET_EXHAUSTED,
                noBid.reason());
    }

    private static DefaultSlotBidWorkflow workflow(
            List<CampaignCandidate> candidates,
            CandidateBidAttempt attempt,
            BidTimePolicy timePolicy
    ) {
        return new DefaultSlotBidWorkflow(
                request -> candidates,
                attempt,
                timePolicy,
                new DefaultCandidateContinuationPolicy(),
                fixedClock()
        );
    }

    private static SlotBidWorkflow.Command slotCommand() {
        var request = new BidRequest("auction-1", 50, List.of(IMPRESSION));
        var authenticated = new AuthenticatedBidRequest("ssp-1", request, NOW);
        return new SlotBidWorkflow.Command(
                new CoordinateBid(authenticated, AuctionDeadline.start(50, System::nanoTime)),
                IMPRESSION
        );
    }

    private static PreparedBid preparedBid(String bidId) {
        return new PreparedBid(
                bidId,
                IMPRESSION.id(),
                SECOND.campaignId(),
                SECOND.creativeId(),
                SECOND.cpmMilliKrw(),
                urls()
        );
    }

    private static ReservationGranted granted() {
        return new ReservationGranted(
                "reservation-1", "lease-1", FIRST.campaignId(), "bid-1",
                FIRST.impressionAmountMicros(), NOW, NOW.plusSeconds(5));
    }

    private static ReservationNoticeUrls urls() {
        return new ReservationNoticeUrls(
                URI.create("https://dsp.example/n/token"),
                URI.create("https://dsp.example/l/token"),
                URI.create("https://dsp.example/b/token")
        );
    }

    private static BidTimePolicy alwaysHasTime() {
        return new FixedBidTimePolicy(true, true);
    }

    private static BidTimePolicy neverHasTime() {
        return new FixedBidTimePolicy(false, false);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private record FixedBidTimePolicy(boolean canWork, boolean canPublish)
            implements BidTimePolicy {

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
    }
}
