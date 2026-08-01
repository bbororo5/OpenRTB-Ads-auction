package com.bbororo.rtb.dsp.notification;

import static com.bbororo.rtb.dsp.contract.ContractChecks.requireAfter;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requireNonBlank;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requirePositive;

import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoticeKind;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/** 예약 통지 URL 발급과 검증에 사용하는 메시지다. */
public final class NotificationMessages {

    private NotificationMessages() {
    }

    public record NotificationUrls(URI noticeUrl, URI lossUrl, URI billingUrl) {
        public NotificationUrls {
            noticeUrl = requireHttpUrl(noticeUrl, "noticeUrl");
            lossUrl = requireHttpUrl(lossUrl, "lossUrl");
            billingUrl = requireHttpUrl(billingUrl, "billingUrl");
        }
    }

    public record NoticeToken(NoticeKind kind, String encodedValue, Instant receivedAt) {
        public NoticeToken {
            Objects.requireNonNull(kind, "kind");
            encodedValue = requireNonBlank(encodedValue, "encodedValue");
            Objects.requireNonNull(receivedAt, "receivedAt");
        }
    }

    public sealed interface NoticeVerification permits VerifiedReservationNotice, InvalidReservationNotice {
    }

    public record VerifiedReservationNotice(
            NoticeKind kind,
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
        WRONG_NOTICE_KIND,
        UNKNOWN_KEY
    }

    private static URI requireHttpUrl(URI uri, String name) {
        Objects.requireNonNull(uri, name);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException(name + " must be an HTTP URL");
        }
        return uri;
    }
}
