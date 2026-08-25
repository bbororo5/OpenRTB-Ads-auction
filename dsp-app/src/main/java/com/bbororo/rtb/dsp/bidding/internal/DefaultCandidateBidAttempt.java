package com.bbororo.rtb.dsp.bidding.internal;

import static com.bbororo.rtb.dsp.contract.ContractChecks.requireNonBlank;

import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.PreparedBid;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.CampaignCandidate;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.IssueReservationNotices;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.NoticeIssuanceFailed;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.NoticesIssued;
import com.bbororo.rtb.dsp.proof.api.ReservationNoticeIssuer;
import com.bbororo.rtb.dsp.spending.api.ReservationAuthority;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationGranted;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationRejected;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.TryReserve;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/** 예약 성공 뒤 통지 발급까지 완료된 경우에만 외부 입찰을 준비한다. */
public final class DefaultCandidateBidAttempt implements CandidateBidAttempt {

    private final ReservationAuthority reservationAuthority;
    private final ReservationNoticeIssuer noticeIssuer;
    private final Clock clock;
    private final Supplier<String> bidIds;
    private final String regionId;
    private final Duration reservationLifetime;

    public DefaultCandidateBidAttempt(
            ReservationAuthority reservationAuthority,
            ReservationNoticeIssuer noticeIssuer,
            Clock clock,
            Supplier<String> bidIds,
            String regionId,
            Duration reservationLifetime
    ) {
        this.reservationAuthority = Objects.requireNonNull(
                reservationAuthority, "reservationAuthority");
        this.noticeIssuer = Objects.requireNonNull(noticeIssuer, "noticeIssuer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.bidIds = Objects.requireNonNull(bidIds, "bidIds");
        this.regionId = requireNonBlank(regionId, "regionId");
        this.reservationLifetime = Objects.requireNonNull(
                reservationLifetime, "reservationLifetime");
        if (reservationLifetime.isZero() || reservationLifetime.isNegative()) {
            throw new IllegalArgumentException("reservationLifetime must be positive");
        }
    }

    @Override
    public Outcome prepare(Command command) {
        Objects.requireNonNull(command, "command");
        Instant reservedAt = clock.instant();
        String bidId = requireNonBlank(
                Objects.requireNonNull(bidIds.get(), "bidId"), "bidId");
        var request = command.bid().request();
        var candidate = command.candidate();
        Instant expiresAt = reservedAt.plus(reservationLifetime);

        var reservation = Objects.requireNonNull(reservationAuthority.tryReserve(new TryReserve(
                request.request().id(),
                command.impression().id(),
                bidId,
                candidate.campaignId(),
                candidate.impressionAmountMicros(),
                reservedAt,
                expiresAt
        )), "reservation result");
        if (reservation instanceof ReservationRejected rejected) {
            return new AttemptRejected(rejected.reason());
        }

        ReservationGranted granted = (ReservationGranted) reservation;
        if (!matches(granted, bidId, candidate, reservedAt, expiresAt)) {
            return new AttemptAbandoned(
                    granted, AbandonmentReason.RESERVATION_CONTRACT_MISMATCH);
        }

        var issuance = noticeIssuer.issue(new IssueReservationNotices(
                request.sspId(), regionId, granted));
        if (issuance == null) {
            return new AttemptAbandoned(
                    granted, AbandonmentReason.NOTICE_ISSUANCE_FAILED);
        }
        return switch (issuance) {
            case NoticesIssued issued -> new AttemptPrepared(new PreparedBid(
                    bidId,
                    command.impression().id(),
                    candidate.campaignId(),
                    candidate.creativeId(),
                    candidate.cpmMilliKrw(),
                    issued.urls()
            ));
            case NoticeIssuanceFailed ignored -> new AttemptAbandoned(
                    granted, AbandonmentReason.NOTICE_ISSUANCE_FAILED);
        };
    }

    private static boolean matches(
            ReservationGranted granted,
            String bidId,
            CampaignCandidate candidate,
            Instant reservedAt,
            Instant expiresAt
    ) {
        return granted.bidId().equals(bidId)
                && granted.campaignId().equals(candidate.campaignId())
                && granted.impressionAmountMicros() == candidate.impressionAmountMicros()
                && granted.reservedAt().equals(reservedAt)
                && granted.expiresAt().equals(expiresAt);
    }
}
