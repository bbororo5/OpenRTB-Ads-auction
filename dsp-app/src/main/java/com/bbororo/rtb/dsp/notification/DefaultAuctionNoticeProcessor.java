package com.bbororo.rtb.dsp.notification;

import static com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.MonetaryEventKind.BILLING;
import static com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.MonetaryEventKind.LOSS;
import static com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.NoticeProcessingStatus.ACCEPTED;
import static com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.NoticeProcessingStatus.CONFLICT;
import static com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.NoticeProcessingStatus.DUPLICATE;
import static com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.NoticeProcessingStatus.LATE_NO_EFFECT;

import com.bbororo.rtb.dsp.budget.BudgetMessages.CommitReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReleaseReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationReference;
import com.bbororo.rtb.dsp.budget.LocalBudgetAuthority;
import com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.EventAlreadyPresent;
import com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.EventAppendResult;
import com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.EventConflict;
import com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.MonetaryEventKind;
import com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.MonetaryNoticeEvent;
import com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.NoticeProcessed;
import com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.NoticeProcessingResult;
import com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.NoticeRejected;
import com.bbororo.rtb.dsp.notification.NotificationMessages.InvalidReservationNotice;
import com.bbororo.rtb.dsp.notification.NotificationMessages.NoticeToken;
import com.bbororo.rtb.dsp.notification.NotificationMessages.VerifiedReservationNotice;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuctionNotice;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoticeKind;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 검증된 외부 통지를 내구 종결 사건으로 만든 뒤 로컬 예약에 재생한다. */
public final class DefaultAuctionNoticeProcessor implements AuctionNoticeProcessor {

    private final ReservationNoticeCodec codec;
    private final MoneyEventJournal journal;
    private final LocalBudgetAuthority localBudget;

    public DefaultAuctionNoticeProcessor(
            ReservationNoticeCodec codec,
            MoneyEventJournal journal,
            LocalBudgetAuthority localBudget
    ) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.localBudget = Objects.requireNonNull(localBudget, "localBudget");
    }

    @Override
    public CompletionStage<NoticeProcessingResult> process(AuctionNotice notice) {
        Objects.requireNonNull(notice, "notice");
        var verification = codec.verify(new NoticeToken(
                notice.kind(), notice.opaqueToken(), notice.receivedAt()
        ));
        if (verification instanceof InvalidReservationNotice) {
            return CompletableFuture.completedFuture(
                    new NoticeRejected(NoticeProcessingMessages.NoticeRejection.INVALID)
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
        return journal.append(event)
                .thenApply(result -> applyLocal(event, result))
                .exceptionally(failure -> new NoticeRejected(
                        NoticeProcessingMessages.NoticeRejection.TEMPORARILY_UNAVAILABLE
                ));
    }

    private NoticeProcessingResult applyLocal(
            MonetaryNoticeEvent event,
            EventAppendResult appendResult
    ) {
        if (appendResult instanceof EventConflict) {
            return new NoticeProcessed(CONFLICT, event.reservationId());
        }
        boolean duplicate = appendResult instanceof EventAlreadyPresent;
        if (event.arrivedAfterDeadline()) {
            return new NoticeProcessed(
                    duplicate ? DUPLICATE : LATE_NO_EFFECT,
                    event.reservationId()
            );
        }

        var reference = new ReservationReference(
                event.campaignId(), event.leaseId(), event.reservationId()
        );
        if (event.kind() == BILLING) {
            localBudget.commit(new CommitReservation(
                    reference,
                    event.impressionAmountMicros(),
                    event.eventId(),
                    event.receivedAt()
            ));
        } else {
            localBudget.release(new ReleaseReservation(
                    reference,
                    event.impressionAmountMicros(),
                    event.eventId(),
                    event.receivedAt()
            ));
        }
        return new NoticeProcessed(duplicate ? DUPLICATE : ACCEPTED, event.reservationId());
    }

    static String eventId(String reservationId, MonetaryEventKind kind) {
        return reservationId + ':' + kind.name();
    }
}
