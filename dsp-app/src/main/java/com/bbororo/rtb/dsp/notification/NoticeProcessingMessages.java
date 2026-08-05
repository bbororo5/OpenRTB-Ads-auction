package com.bbororo.rtb.dsp.notification;

import static com.bbororo.rtb.dsp.contract.ContractChecks.requireNonBlank;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requirePositive;

import java.time.Instant;
import java.util.Objects;

/** 경매 결과 통지의 내구 기록과 예약 종결에 사용하는 메시지다. */
public final class NoticeProcessingMessages {

    private NoticeProcessingMessages() {
    }

    public record MonetaryNoticeEvent(
            String eventId,
            MonetaryEventKind kind,
            String reservationId,
            String leaseId,
            String campaignId,
            long impressionAmountMicros,
            Instant reservationExpiresAt,
            Instant receivedAt
    ) {
        public MonetaryNoticeEvent {
            eventId = requireNonBlank(eventId, "eventId");
            Objects.requireNonNull(kind, "kind");
            reservationId = requireNonBlank(reservationId, "reservationId");
            leaseId = requireNonBlank(leaseId, "leaseId");
            campaignId = requireNonBlank(campaignId, "campaignId");
            requirePositive(impressionAmountMicros, "impressionAmountMicros");
            Objects.requireNonNull(reservationExpiresAt, "reservationExpiresAt");
            Objects.requireNonNull(receivedAt, "receivedAt");
        }

        public boolean arrivedAfterDeadline() {
            return receivedAt.isAfter(reservationExpiresAt);
        }
    }

    public sealed interface EventAppendResult permits EventAppended, EventAlreadyPresent, EventConflict {
    }

    public record EventAppended(String eventId) implements EventAppendResult {
        public EventAppended {
            eventId = requireNonBlank(eventId, "eventId");
        }
    }

    public record EventAlreadyPresent(String eventId) implements EventAppendResult {
        public EventAlreadyPresent {
            eventId = requireNonBlank(eventId, "eventId");
        }
    }

    public record EventConflict(String eventId, MonetaryEventKind existingKind)
            implements EventAppendResult {
        public EventConflict {
            eventId = requireNonBlank(eventId, "eventId");
            Objects.requireNonNull(existingKind, "existingKind");
        }
    }

    public sealed interface NoticeProcessingResult permits NoticeProcessed, NoticeRejected {
    }

    public record NoticeProcessed(NoticeProcessingStatus status, String reservationId)
            implements NoticeProcessingResult {
        public NoticeProcessed {
            Objects.requireNonNull(status, "status");
            reservationId = requireNonBlank(reservationId, "reservationId");
        }
    }

    public record NoticeRejected(NoticeRejection reason) implements NoticeProcessingResult {
        public NoticeRejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public enum NoticeProcessingStatus {
        ACCEPTED,
        DUPLICATE,
        LATE_NO_EFFECT,
        CONFLICT
    }

    public enum MonetaryEventKind {
        LOSS,
        BILLING,
        EXPIRY
    }

    public enum NoticeRejection {
        INVALID,
        TEMPORARILY_UNAVAILABLE
    }
}
