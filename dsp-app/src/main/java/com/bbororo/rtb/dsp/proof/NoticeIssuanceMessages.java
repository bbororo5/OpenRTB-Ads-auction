package com.bbororo.rtb.dsp.proof;

import static com.bbororo.rtb.dsp.contract.ContractChecks.requireAfter;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requireNonBlank;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requirePositive;

import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationGranted;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoticeKind;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/** 예약 사실을 외부 통지 주소로 발급할 때 주고받는 불변 메시지다. */
public final class NoticeIssuanceMessages {

    private NoticeIssuanceMessages() {
    }

    public record IssueReservationNotices(
            String authenticatedSspId,
            String regionId,
            ReservationGranted reservation
    ) {
        public IssueReservationNotices {
            authenticatedSspId = requireNonBlank(authenticatedSspId, "authenticatedSspId");
            regionId = requireNonBlank(regionId, "regionId");
            Objects.requireNonNull(reservation, "reservation");
        }
    }

    public record ComposeReservationNoticeClaims(
            String authenticatedSspId,
            String regionId,
            ReservationGranted reservation
    ) {
        public ComposeReservationNoticeClaims {
            authenticatedSspId = requireNonBlank(authenticatedSspId, "authenticatedSspId");
            regionId = requireNonBlank(regionId, "regionId");
            Objects.requireNonNull(reservation, "reservation");
        }
    }

    public record ReservationNoticeClaims(
            String authenticatedSspId,
            String regionId,
            String reservationId,
            String leaseId,
            String campaignId,
            String bidId,
            long impressionAmountMicros,
            Instant reservedAt,
            Instant expiresAt
    ) {
        public ReservationNoticeClaims {
            authenticatedSspId = requireNonBlank(authenticatedSspId, "authenticatedSspId");
            regionId = requireNonBlank(regionId, "regionId");
            reservationId = requireNonBlank(reservationId, "reservationId");
            leaseId = requireNonBlank(leaseId, "leaseId");
            campaignId = requireNonBlank(campaignId, "campaignId");
            bidId = requireNonBlank(bidId, "bidId");
            requirePositive(impressionAmountMicros, "impressionAmountMicros");
            requireAfter(reservedAt, expiresAt, "expiresAt");
        }
    }

    public record SealReservationNotice(
            NoticeKind kind,
            ReservationNoticeClaims claims
    ) {
        public SealReservationNotice {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(claims, "claims");
        }
    }

    public record SealedReservationNotice(
            NoticeKind kind,
            String encodedToken
    ) {
        public SealedReservationNotice {
            Objects.requireNonNull(kind, "kind");
            encodedToken = requireNonBlank(encodedToken, "encodedToken");
        }
    }

    public record NoticeUrl(NoticeKind kind, URI value) {
        public NoticeUrl {
            Objects.requireNonNull(kind, "kind");
            value = requireHttpUrl(value, "value");
        }
    }

    public record ReservationNoticeUrls(
            URI winNoticeUrl,
            URI lossNoticeUrl,
            URI billingNoticeUrl
    ) {
        public ReservationNoticeUrls {
            winNoticeUrl = requireHttpUrl(winNoticeUrl, "winNoticeUrl");
            lossNoticeUrl = requireHttpUrl(lossNoticeUrl, "lossNoticeUrl");
            billingNoticeUrl = requireHttpUrl(billingNoticeUrl, "billingNoticeUrl");
        }
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
