package com.bbororo.rtb.dsp.notification;

import static com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.MonetaryEventKind.EXPIRY;

import com.bbororo.rtb.dsp.budget.BudgetMessages.ExpireReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationExpiration;
import com.bbororo.rtb.dsp.budget.LocalBudgetAuthority;
import com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.EventConflict;
import com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.MonetaryNoticeEvent;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** 예약 만료를 먼저 내구 종결 사건으로 기록하고 로컬 권한에 재생한다. */
public final class ReservationExpirationService {

    private final MoneyEventJournal journal;
    private final LocalBudgetAuthority localBudget;

    public ReservationExpirationService(MoneyEventJournal journal, LocalBudgetAuthority localBudget) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.localBudget = Objects.requireNonNull(localBudget, "localBudget");
    }

    public CompletionStage<Boolean> expire(ReservationExpiration expiration) {
        Objects.requireNonNull(expiration, "expiration");
        var reference = expiration.reservation();
        var event = new MonetaryNoticeEvent(
                DefaultAuctionNoticeProcessor.eventId(reference.reservationId(), EXPIRY),
                EXPIRY,
                reference.reservationId(),
                reference.leaseId(),
                reference.campaignId(),
                expiration.impressionAmountMicros(),
                expiration.expiresAt(),
                expiration.expiresAt()
        );
        return journal.append(event).thenApply(result -> {
            localBudget.expire(new ExpireReservation(
                    reference,
                    expiration.impressionAmountMicros(),
                    event.eventId(),
                    expiration.expiresAt()
            ));
            return !(result instanceof EventConflict);
        });
    }
}
