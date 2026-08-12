package com.bbororo.rtb.dsp.notification;

import static com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationFinalization.APPLIED;
import static com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.MonetaryEventKind.BILLING;
import static com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.NoticeProcessingStatus.DUPLICATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.bbororo.rtb.dsp.budget.BudgetMessages.CommitReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ExpireReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.InstallLease;
import com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseInstallResult;
import com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseSupplySnapshot;
import com.bbororo.rtb.dsp.budget.BudgetMessages.PacingPosition;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReleaseReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationExpiration;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationFinalization;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationReference;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationResult;
import com.bbororo.rtb.dsp.budget.BudgetMessages.TryReserve;
import com.bbororo.rtb.dsp.budget.LocalBudgetAuthority;
import com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.EventAlreadyPresent;
import com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.EventConflict;
import com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.NoticeProcessed;
import com.bbororo.rtb.dsp.notification.NoticeVerificationMessages.NoticeToken;
import com.bbororo.rtb.dsp.notification.NoticeVerificationMessages.NoticeVerification;
import com.bbororo.rtb.dsp.notification.NoticeVerificationMessages.VerifiedReservationNotice;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuctionNotice;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoticeKind;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NoticeFinalizationServiceTest {

    private static final Instant RESERVED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRES_AT = RESERVED_AT.plusSeconds(2);

    @Test
    void retryAfterDurableAppendReplaysTheLocalCommit() {
        var local = new CapturingLocalBudget();
        MoneyEventJournal journal = event -> CompletableFuture.completedFuture(
                new EventAlreadyPresent(event.eventId())
        );
        var processor = new DefaultAuctionNoticeProcessor(codec(), journal, local);

        var result = processor.process(new AuctionNotice(
                "ssp-1", NoticeKind.BILLING, "token", RESERVED_AT.plusSeconds(1)
        )).toCompletableFuture().join();

        assertEquals(DUPLICATE, ((NoticeProcessed) result).status());
        assertNotNull(local.committed);
    }

    @Test
    void expirationStillClosesLocalStateWhenAnotherTerminalEventWon() {
        var local = new CapturingLocalBudget();
        MoneyEventJournal journal = event -> CompletableFuture.completedFuture(
                new EventConflict(event.eventId(), BILLING)
        );
        var service = new ReservationExpirationService(journal, local);
        var reference = new ReservationReference("campaign-1", leaseId(), "reservation-1");

        boolean cleanOutcome = service.expire(new ReservationExpiration(
                reference, 1_000, EXPIRES_AT
        )).toCompletableFuture().join();

        assertEquals(false, cleanOutcome);
        assertNotNull(local.expired);
    }

    @Test
    void expirationWorkerRetriesTheSameMarkerAfterStorageFailure() throws Exception {
        var local = new CapturingLocalBudget();
        AtomicInteger attempts = new AtomicInteger();
        MoneyEventJournal journal = event -> attempts.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new IllegalStateException("store unavailable"))
                : CompletableFuture.completedFuture(
                        new NoticeProcessingMessages.EventAppended(event.eventId())
                );
        var service = new ReservationExpirationService(journal, local);
        var queue = new ArrayBlockingQueue<ReservationExpiration>(1);
        queue.add(new ReservationExpiration(
                new ReservationReference("campaign-1", leaseId(), "reservation-1"),
                1_000,
                EXPIRES_AT
        ));

        try (var worker = new ReservationExpirationWorker(
                queue::take, service, Duration.ofMillis(1), failure -> { }
        )) {
            worker.start();
            org.junit.jupiter.api.Assertions.assertTrue(local.expiredLatch.await(2, TimeUnit.SECONDS));
        }

        assertEquals(2, attempts.get());
    }

    private static ReservationNoticeVerifier codec() {
        return new ReservationNoticeVerifier() {
            @Override
            public NoticeVerification verify(NoticeToken token) {
                return new VerifiedReservationNotice(
                        token.kind(), "reservation-1", leaseId(), "campaign-1", "bid-1",
                        1_000, RESERVED_AT, EXPIRES_AT, token.receivedAt()
                );
            }
        };
    }

    private static String leaseId() {
        return "2d981df7-40e4-453e-b708-c23a86efca68";
    }

    private static final class CapturingLocalBudget implements LocalBudgetAuthority {
        private CommitReservation committed;
        private ExpireReservation expired;
        private final CountDownLatch expiredLatch = new CountDownLatch(1);

        @Override public ReservationResult tryReserve(TryReserve command) { throw new UnsupportedOperationException(); }
        @Override public ReservationFinalization release(ReleaseReservation command) { return APPLIED; }
        @Override public ReservationFinalization commit(CommitReservation command) { committed = command; return APPLIED; }
        @Override public ReservationFinalization expire(ExpireReservation command) {
            expired = command;
            expiredLatch.countDown();
            return APPLIED;
        }
        @Override public LeaseInstallResult install(InstallLease command, long requestStartedNanos) { throw new UnsupportedOperationException(); }
        @Override public PacingPosition positionOf(String campaignId) { return new PacingPosition(false, 0); }
        @Override public List<LeaseSupplySnapshot> supplySnapshots() { return List.of(); }
    }
}
