package com.bbororo.rtb.dsp.outcome;

import static com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.MonetaryEventKind.BILLING;
import static com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.MonetaryEventKind.EXPIRY;

import com.bbororo.rtb.dsp.spending.SpendingMessages.CommitReservation;
import com.bbororo.rtb.dsp.spending.SpendingMessages.ExpireReservation;
import com.bbororo.rtb.dsp.spending.SpendingMessages.ReleaseReservation;
import com.bbororo.rtb.dsp.spending.SpendingMessages.ReservationReference;
import com.bbororo.rtb.dsp.spending.LocalSpendingAuthority;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.MonetaryNoticeEvent;
import java.util.Objects;

/** 내구적으로 선택된 결과만 로컬 예약 상태에 멱등 재생한다. */
final class ReservationOutcomeReplayer {

    private final LocalSpendingAuthority localBudget;

    ReservationOutcomeReplayer(LocalSpendingAuthority localBudget) {
        this.localBudget = Objects.requireNonNull(localBudget, "localBudget");
    }

    void replay(MonetaryNoticeEvent outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome.kind() != EXPIRY && outcome.arrivedAfterDeadline()) {
            return;
        }

        var reference = new ReservationReference(
                outcome.campaignId(), outcome.leaseId(), outcome.reservationId()
        );
        if (outcome.kind() == BILLING) {
            localBudget.commit(new CommitReservation(
                    reference,
                    outcome.impressionAmountMicros(),
                    outcome.eventId(),
                    outcome.receivedAt()
            ));
        } else if (outcome.kind() == EXPIRY) {
            localBudget.expire(new ExpireReservation(
                    reference,
                    outcome.impressionAmountMicros(),
                    outcome.eventId(),
                    outcome.receivedAt()
            ));
        } else {
            localBudget.release(new ReleaseReservation(
                    reference,
                    outcome.impressionAmountMicros(),
                    outcome.eventId(),
                    outcome.receivedAt()
            ));
        }
    }
}
