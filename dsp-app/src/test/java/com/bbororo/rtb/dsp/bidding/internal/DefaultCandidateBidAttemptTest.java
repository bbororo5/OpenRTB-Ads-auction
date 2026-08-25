package com.bbororo.rtb.dsp.bidding.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.CoordinateBid;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.CampaignCandidate;
import com.bbororo.rtb.dsp.contract.AuctionDeadline;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuthenticatedBidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Impression;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.NoticeIssuanceFailed;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.NoticeIssuanceFailure;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.NoticesIssued;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.ReservationNoticeUrls;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationGranted;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationRejected;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationRejection;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.TryReserve;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DefaultCandidateBidAttemptTest {

    private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");
    private static final Duration RESERVATION_LIFETIME = Duration.ofSeconds(5);
    private static final Impression IMPRESSION = new Impression("imp-1", 300, 250, 500, 2);
    private static final CampaignCandidate CANDIDATE =
            new CampaignCandidate("campaign-1", "creative-1", 2_000, 10);
    private static final ReservationNoticeUrls URLS = new ReservationNoticeUrls(
            URI.create("https://dsp.example/n/token"),
            URI.create("https://dsp.example/l/token"),
            URI.create("https://dsp.example/b/token")
    );

    @Test
    void preparesABidOnlyAfterReservationAndAllNoticesSucceed() {
        var captured = new AtomicReference<TryReserve>();
        var granted = granted("campaign-1", "bid-1", 2_000);
        var attempt = new DefaultCandidateBidAttempt(
                command -> {
                    captured.set(command);
                    return granted;
                },
                command -> new NoticesIssued(URLS),
                fixedClock(),
                () -> "bid-1",
                "ap-northeast-2",
                RESERVATION_LIFETIME
        );

        var prepared = assertInstanceOf(
                CandidateBidAttempt.AttemptPrepared.class,
                attempt.prepare(command(CANDIDATE))
        );

        assertEquals("bid-1", prepared.bid().bidId());
        assertEquals("imp-1", prepared.bid().impressionId());
        assertEquals(URLS, prepared.bid().notificationUrls());
        assertEquals(NOW, captured.get().reservedAt());
        assertEquals(NOW.plusSeconds(5), captured.get().expiresAt());
    }

    @Test
    void preservesTypedReservationRejectionWithoutIssuingNotices() {
        var attempt = new DefaultCandidateBidAttempt(
                command -> new ReservationRejected(ReservationRejection.CONTENDED),
                command -> {
                    throw new AssertionError("notices must not be issued");
                },
                fixedClock(),
                () -> "bid-1",
                "ap-northeast-2",
                RESERVATION_LIFETIME
        );

        var rejected = assertInstanceOf(
                CandidateBidAttempt.AttemptRejected.class,
                attempt.prepare(command(CANDIDATE))
        );

        assertEquals(ReservationRejection.CONTENDED, rejected.reason());
    }

    @Test
    void marksGrantedReservationAbandonedWhenNoticeIssuanceFails() {
        var granted = granted("campaign-1", "bid-1", 2_000);
        var attempt = new DefaultCandidateBidAttempt(
                command -> granted,
                command -> new NoticeIssuanceFailed(
                        NoticeIssuanceFailure.TECHNICAL_FAILURE),
                fixedClock(),
                () -> "bid-1",
                "ap-northeast-2",
                RESERVATION_LIFETIME
        );

        var abandoned = assertInstanceOf(
                CandidateBidAttempt.AttemptAbandoned.class,
                attempt.prepare(command(CANDIDATE))
        );

        assertEquals(granted, abandoned.reservation());
        assertEquals(CandidateBidAttempt.AbandonmentReason.NOTICE_ISSUANCE_FAILED,
                abandoned.reason());
    }

    @Test
    void marksMismatchedReservationAbandonedBeforeNoticeIssuance() {
        var mismatched = granted("other-campaign", "bid-1", 2_000);
        var attempt = new DefaultCandidateBidAttempt(
                command -> mismatched,
                command -> {
                    throw new AssertionError("mismatched reservation must not be sealed");
                },
                fixedClock(),
                () -> "bid-1",
                "ap-northeast-2",
                RESERVATION_LIFETIME
        );

        var abandoned = assertInstanceOf(
                CandidateBidAttempt.AttemptAbandoned.class,
                attempt.prepare(command(CANDIDATE))
        );

        assertEquals(CandidateBidAttempt.AbandonmentReason.RESERVATION_CONTRACT_MISMATCH,
                abandoned.reason());
    }

    private static CandidateBidAttempt.Command command(CampaignCandidate candidate) {
        return new CandidateBidAttempt.Command(coordinateBid(), IMPRESSION, candidate);
    }

    private static CoordinateBid coordinateBid() {
        var request = new BidRequest("auction-1", 50, List.of(IMPRESSION));
        return new CoordinateBid(
                new AuthenticatedBidRequest("ssp-1", request, NOW),
                AuctionDeadline.start(50, System::nanoTime)
        );
    }

    private static ReservationGranted granted(String campaignId, String bidId, long amount) {
        return new ReservationGranted(
                "reservation-1",
                "lease-1",
                campaignId,
                bidId,
                amount,
                NOW,
                NOW.plus(RESERVATION_LIFETIME)
        );
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
