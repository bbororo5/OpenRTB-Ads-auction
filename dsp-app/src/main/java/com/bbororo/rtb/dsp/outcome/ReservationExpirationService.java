package com.bbororo.rtb.dsp.outcome;

import static com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.MonetaryEventKind.EXPIRY;

import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationExpiration;
import com.bbororo.rtb.dsp.spending.api.ReservationFinalizer;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.MonetaryNoticeEvent;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.OutcomeConflict;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.OutcomeIgnored;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** 예약 만료를 먼저 내구 종결 사건으로 기록하고 로컬 권한에 재생한다. */
public final class ReservationExpirationService {

    private final ReservationOutcomeStore journal;
    private final ReservationOutcomeReplayer replayer;

    public ReservationExpirationService(ReservationOutcomeStore journal, ReservationFinalizer localBudget) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.replayer = new ReservationOutcomeReplayer(localBudget);
    }

    public CompletionStage<Boolean> expire(ReservationExpiration expiration) {
        Objects.requireNonNull(expiration, "expiration");
        var reference = expiration.reservation();
        var event = new MonetaryNoticeEvent(
                DefaultReservationOutcomeProcessor.eventId(reference.reservationId(), EXPIRY),
                EXPIRY,
                reference.reservationId(),
                reference.leaseId(),
                reference.campaignId(),
                expiration.impressionAmountMicros(),
                expiration.expiresAt(),
                expiration.expiresAt()
        );
        return journal.decide(event).thenApply(decision -> {
            if (decision instanceof OutcomeIgnored) {
                throw new IllegalStateException("expiration cannot be ignored");
            }
            replayer.replay(decision.outcome());
            return !(decision instanceof OutcomeConflict);
        });
    }
}
