package com.bbororo.rtb.dsp.outcome;

import static com.bbororo.rtb.dsp.contract.ContractChecks.requireNonBlank;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requirePositive;

import java.time.Instant;
import java.util.Objects;

/** 경매 결과 통지의 내구 기록과 예약 종결에 사용하는 메시지다. */
public final class ReservationOutcomeMessages {

    private ReservationOutcomeMessages() {
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

    /** 저장소가 선택한 예약의 canonical 금액 결과다. */
    public sealed interface OutcomeDecision permits OutcomeChosen, OutcomeConflict, OutcomeIgnored {

        MonetaryNoticeEvent outcome();
    }

    public record OutcomeChosen(MonetaryNoticeEvent outcome, boolean firstDecision)
            implements OutcomeDecision {
        public OutcomeChosen {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    public record OutcomeConflict(MonetaryNoticeEvent outcome, MonetaryEventKind rejectedKind)
            implements OutcomeDecision {
        public OutcomeConflict {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(rejectedKind, "rejectedKind");
        }
    }

    /** 감사 사건은 기록했지만 금액 종결 후보로 채택하지 않은 결과다. */
    public record OutcomeIgnored(MonetaryNoticeEvent outcome, boolean firstObservation)
            implements OutcomeDecision {
        public OutcomeIgnored {
            Objects.requireNonNull(outcome, "outcome");
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
