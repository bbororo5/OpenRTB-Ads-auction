package com.bbororo.rtb.dsp.bidding.api;

import com.bbororo.rtb.dsp.bidding.internal.DeadlineBidTimePolicy;
import com.bbororo.rtb.dsp.bidding.internal.DefaultBidCoordinator;
import com.bbororo.rtb.dsp.bidding.internal.DefaultBidExecutionGate;
import com.bbororo.rtb.dsp.bidding.internal.DefaultBidRequestExecutor;
import com.bbororo.rtb.dsp.bidding.internal.DefaultCandidateBidAttempt;
import com.bbororo.rtb.dsp.bidding.internal.DefaultCandidateContinuationPolicy;
import com.bbororo.rtb.dsp.bidding.internal.DefaultSlotBidWorkflow;
import com.bbororo.rtb.dsp.bidding.internal.Sha256BidRequestFingerprintCalculator;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignCandidateSource;
import com.bbororo.rtb.dsp.proof.api.ReservationNoticeIssuer;
import com.bbororo.rtb.dsp.spending.api.ReservationAuthority;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Bidding 컴포넌트의 internal 구현을 숨기고 완성된 요청 실행 포트를 반환한다. */
public final class BiddingComponentFactory {

    private BiddingComponentFactory() {
    }

    public static BidRequestExecutor create(
            Settings settings,
            CampaignCandidateSource campaigns,
            ReservationAuthority reservations,
            ReservationNoticeIssuer noticeIssuer,
            Clock clock,
            LongSupplier monotonicNanos,
            Supplier<String> bidIds
    ) {
        Objects.requireNonNull(settings, "settings");
        var timePolicy = new DeadlineBidTimePolicy(
                settings.candidateAttemptCost(),
                settings.publicationReserve()
        );
        var candidateAttempt = new DefaultCandidateBidAttempt(
                reservations,
                noticeIssuer,
                clock,
                bidIds,
                settings.regionId(),
                settings.reservationLifetime()
        );
        var slotWorkflow = new DefaultSlotBidWorkflow(
                campaigns,
                candidateAttempt,
                timePolicy,
                new DefaultCandidateContinuationPolicy(),
                clock
        );
        var coordinator = new DefaultBidCoordinator(slotWorkflow, timePolicy);
        return new DefaultBidRequestExecutor(
                new Sha256BidRequestFingerprintCalculator(),
                new DefaultBidExecutionGate(
                        settings.executionRetention(),
                        settings.executionMaximumEntries(),
                        monotonicNanos
                ),
                coordinator
        );
    }

    public record Settings(
            String regionId,
            Duration reservationLifetime,
            Duration candidateAttemptCost,
            Duration publicationReserve,
            Duration executionRetention,
            int executionMaximumEntries
    ) {
        public Settings {
            if (regionId == null || regionId.isBlank()) {
                throw new IllegalArgumentException("regionId must not be blank");
            }
            Objects.requireNonNull(reservationLifetime, "reservationLifetime");
            Objects.requireNonNull(candidateAttemptCost, "candidateAttemptCost");
            Objects.requireNonNull(publicationReserve, "publicationReserve");
            Objects.requireNonNull(executionRetention, "executionRetention");
            if (reservationLifetime.isZero() || reservationLifetime.isNegative()) {
                throw new IllegalArgumentException("reservationLifetime must be positive");
            }
            if (candidateAttemptCost.isNegative() || publicationReserve.isNegative()) {
                throw new IllegalArgumentException("bid time reserves must not be negative");
            }
            if (executionRetention.isZero() || executionRetention.isNegative()) {
                throw new IllegalArgumentException("executionRetention must be positive");
            }
            if (executionMaximumEntries <= 0) {
                throw new IllegalArgumentException("executionMaximumEntries must be positive");
            }
        }
    }
}
