package com.bbororo.rtb.dsp.outcome;

import static com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.MonetaryEventKind.BILLING;
import static com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.MonetaryEventKind.LOSS;
import static com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.NoticeProcessingStatus.ACCEPTED;
import static com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.NoticeProcessingStatus.CONFLICT;
import static com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.NoticeProcessingStatus.DUPLICATE;
import static com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.NoticeProcessingStatus.LATE_NO_EFFECT;

import com.bbororo.rtb.dsp.spending.LocalSpendingAuthority;
import com.bbororo.rtb.dsp.proof.NoticeVerificationMessages.InvalidReservationNotice;
import com.bbororo.rtb.dsp.proof.NoticeVerificationMessages.NoticeToken;
import com.bbororo.rtb.dsp.proof.NoticeVerificationMessages.VerifiedReservationNotice;
import com.bbororo.rtb.dsp.proof.ReservationNoticeVerifier;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.MonetaryEventKind;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.MonetaryNoticeEvent;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.NoticeProcessed;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.NoticeProcessingResult;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.NoticeRejected;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.OutcomeChosen;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.OutcomeDecision;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.OutcomeConflict;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuctionNotice;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoticeKind;
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
            LocalSpendingAuthority localBudget
    ) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.replayer = new ReservationOutcomeReplayer(localBudget);
    }

    @Override
    public CompletionStage<NoticeProcessingResult> process(AuctionNotice notice) {
        Objects.requireNonNull(notice, "notice");
        var verification = verifier.verify(new NoticeToken(
                notice.kind(), notice.opaqueToken(), notice.receivedAt()
        ));
        if (verification instanceof InvalidReservationNotice) {
            return CompletableFuture.completedFuture(
                    new NoticeRejected(ReservationOutcomeMessages.NoticeRejection.INVALID)
            );
        }
        var verified = (VerifiedReservationNotice) verification;
        if (verified.kind() == NoticeKind.WIN) {
            return CompletableFuture.completedFuture(
                    new NoticeProcessed(ACCEPTED, verified.reservationId())
            );
        }

        MonetaryEventKind kind = verified.kind() == NoticeKind.BILLING ? BILLING : LOSS;
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
        replayer.replay(outcome);
        if (decision instanceof OutcomeConflict) {
            return new NoticeProcessed(CONFLICT, outcome.reservationId());
        }
        var chosen = (OutcomeChosen) decision;
        if (outcome.arrivedAfterDeadline()) {
            return new NoticeProcessed(
                    chosen.firstDecision() ? LATE_NO_EFFECT : DUPLICATE,
                    outcome.reservationId()
            );
        }
        return new NoticeProcessed(
                chosen.firstDecision() ? ACCEPTED : DUPLICATE,
                outcome.reservationId()
        );
    }

    static String eventId(String reservationId, MonetaryEventKind kind) {
        return reservationId + ':' + kind.name();
    }
}
