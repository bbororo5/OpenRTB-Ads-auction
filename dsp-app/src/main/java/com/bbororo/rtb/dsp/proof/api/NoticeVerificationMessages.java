package com.bbororo.rtb.dsp.proof.api;

import static com.bbororo.rtb.dsp.contract.ContractChecks.requireAfter;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requireNonBlank;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requirePositive;

import com.bbororo.rtb.dsp.proof.api.ReservationNoticeKind;
import java.time.Instant;
import java.util.Objects;

/** 외부에서 받은 예약 통지 증표를 검증할 때 주고받는 불변 메시지다. */
public final class NoticeVerificationMessages {

    private NoticeVerificationMessages() {
    }

    public record NoticeToken(
            String authenticatedSspId,
            ReservationNoticeKind kind,
            String encodedValue,
            Instant receivedAt
    ) {
        public NoticeToken {
            authenticatedSspId = requireNonBlank(authenticatedSspId, "authenticatedSspId");
            Objects.requireNonNull(kind, "kind");
            encodedValue = requireNonBlank(encodedValue, "encodedValue");
            Objects.requireNonNull(receivedAt, "receivedAt");
        }
    }

    public sealed interface NoticeVerification permits VerifiedReservationNotice, InvalidReservationNotice {
    }

    public record VerifiedReservationNotice(
            ReservationNoticeKind kind,
            String reservationId,
            String leaseId,
            String campaignId,
            String bidId,
            long impressionAmountMicros,
            Instant reservedAt,
            Instant expiresAt,
            Instant receivedAt
    ) implements NoticeVerification {
        public VerifiedReservationNotice {
            Objects.requireNonNull(kind, "kind");
            reservationId = requireNonBlank(reservationId, "reservationId");
            leaseId = requireNonBlank(leaseId, "leaseId");
            campaignId = requireNonBlank(campaignId, "campaignId");
            bidId = requireNonBlank(bidId, "bidId");
            requirePositive(impressionAmountMicros, "impressionAmountMicros");
            requireAfter(reservedAt, expiresAt, "expiresAt");
            Objects.requireNonNull(receivedAt, "receivedAt");
        }

        public boolean arrivedAfterDeadline() {
            return receivedAt.isAfter(expiresAt);
        }
    }

    public record InvalidReservationNotice(InvalidNoticeReason reason) implements NoticeVerification {
        public InvalidReservationNotice {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public enum InvalidNoticeReason {
        MALFORMED,
        AUTHENTICATION_FAILED,
        WRONG_SSP,
        WRONG_NOTICE_KIND,
        UNKNOWN_KEY
    }
}
