package com.bbororo.rtb.dsp.outcome.internal;

import static com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.MonetaryEventKind.BILLING;
import static com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.MonetaryEventKind.LOSS;
import static com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.NoticeProcessingStatus.ACCEPTED;
import static com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.NoticeProcessingStatus.CONFLICT;
import static com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.NoticeProcessingStatus.DUPLICATE;
import static com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.NoticeProcessingStatus.LATE_NO_EFFECT;

import com.bbororo.rtb.dsp.spending.api.ReservationFinalizer;
import com.bbororo.rtb.dsp.proof.api.NoticeVerificationMessages.InvalidReservationNotice;
import com.bbororo.rtb.dsp.proof.api.NoticeVerificationMessages.NoticeToken;
import com.bbororo.rtb.dsp.proof.api.NoticeVerificationMessages.VerifiedReservationNotice;
import com.bbororo.rtb.dsp.proof.api.ReservationNoticeVerifier;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.MonetaryEventKind;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.MonetaryNoticeEvent;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.NoticeProcessed;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.NoticeProcessingResult;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.NoticeRejected;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.OutcomeChosen;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.OutcomeDecision;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.OutcomeConflict;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.OutcomeIgnored;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.ProcessReservationNotice;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeProcessor;
import com.bbororo.rtb.dsp.outcome.spi.ReservationOutcomeStore;
import com.bbororo.rtb.dsp.proof.api.ReservationNoticeKind;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 검증된 외부 통지를 내구 종결 사건으로 만든 뒤 로컬 예약에 재생한다. */
public final class DefaultReservationOutcomeProcessor implements ReservationOutcomeProcessor {

    private final ReservationNoticeVerifier verifier;
    private final ReservationOutcomeStore journal;
    private final ReservationOutcomeReplayer replayer;

    public DefaultReservationOutcomeProcessor(
            ReservationNoticeVerifier verifier,
            ReservationOutcomeStore journal,
            ReservationFinalizer localBudget
    ) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.replayer = new ReservationOutcomeReplayer(localBudget);
    }

    @Override
    public CompletionStage<NoticeProcessingResult> process(ProcessReservationNotice notice) {
        Objects.requireNonNull(notice, "notice");
        var verification = verifier.verify(new NoticeToken(
                notice.authenticatedSspId(), notice.kind(), notice.opaqueToken(), notice.receivedAt()
        ));
        if (verification instanceof InvalidReservationNotice) {
            return CompletableFuture.completedFuture(
                    new NoticeRejected(ReservationOutcomeMessages.NoticeRejection.INVALID)
            );
        }
        var verified = (VerifiedReservationNotice) verification;
        if (verified.kind() == ReservationNoticeKind.WIN) {
            return CompletableFuture.completedFuture(
                    new NoticeProcessed(ACCEPTED, verified.reservationId())
            );
        }

        MonetaryEventKind kind = verified.kind() == ReservationNoticeKind.BILLING ? BILLING : LOSS;
        var event = new MonetaryNoticeEvent(
                eventId(verified.reservationId(), kind),
                kind,
                verified.reservationId(),
                verified.leaseId(),
                verified.campaignId(),
                verified.impressionAmountMicros(),
                verified.expiresAt(),
                verified.receivedAt()
        );
        return journal.decide(event)
                .thenApply(this::applyDecision)
                .exceptionally(failure -> new NoticeRejected(
                        ReservationOutcomeMessages.NoticeRejection.TEMPORARILY_UNAVAILABLE
                ));
    }

    private NoticeProcessingResult applyDecision(OutcomeDecision decision) {
        MonetaryNoticeEvent outcome = decision.outcome();
        if (decision instanceof OutcomeIgnored ignored) {
            return new NoticeProcessed(
                    ignored.firstObservation() ? LATE_NO_EFFECT : DUPLICATE,
                    outcome.reservationId()
            );
        }
        replayer.replay(outcome);
        if (decision instanceof OutcomeConflict) {
            return new NoticeProcessed(CONFLICT, outcome.reservationId());
        }
        var chosen = (OutcomeChosen) decision;
        return new NoticeProcessed(
                chosen.firstDecision() ? ACCEPTED : DUPLICATE,
                outcome.reservationId()
        );
    }

    static String eventId(String reservationId, MonetaryEventKind kind) {
        return reservationId + ':' + kind.name();
    }
}
