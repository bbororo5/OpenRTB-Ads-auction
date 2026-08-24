package com.bbororo.rtb.dsp.outcome;

import static com.bbororo.rtb.dsp.spending.SpendingMessages.ReservationFinalization.APPLIED;
import static com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.MonetaryEventKind.BILLING;
import static com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.NoticeProcessingStatus.DUPLICATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.bbororo.rtb.dsp.spending.SpendingMessages.CommitReservation;
import com.bbororo.rtb.dsp.spending.SpendingMessages.ExpireReservation;
import com.bbororo.rtb.dsp.spending.SpendingMessages.InstallLease;
import com.bbororo.rtb.dsp.spending.SpendingMessages.LeaseInstallResult;
import com.bbororo.rtb.dsp.spending.SpendingMessages.LeaseSupplySnapshot;
import com.bbororo.rtb.dsp.spending.SpendingMessages.PacingPosition;
import com.bbororo.rtb.dsp.spending.SpendingMessages.ReleaseReservation;
import com.bbororo.rtb.dsp.spending.SpendingMessages.ReservationExpiration;
import com.bbororo.rtb.dsp.spending.SpendingMessages.ReservationFinalization;
import com.bbororo.rtb.dsp.spending.SpendingMessages.ReservationReference;
import com.bbororo.rtb.dsp.spending.SpendingMessages.ReservationResult;
import com.bbororo.rtb.dsp.spending.SpendingMessages.TryReserve;
import com.bbororo.rtb.dsp.spending.LocalSpendingAuthority;
import com.bbororo.rtb.dsp.proof.NoticeVerificationMessages.NoticeToken;
import com.bbororo.rtb.dsp.proof.NoticeVerificationMessages.NoticeVerification;
import com.bbororo.rtb.dsp.proof.NoticeVerificationMessages.VerifiedReservationNotice;
import com.bbororo.rtb.dsp.proof.ReservationNoticeVerifier;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.NoticeProcessed;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.MonetaryNoticeEvent;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.OutcomeChosen;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.OutcomeConflict;
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

class ReservationOutcomeProcessorTest {

    private static final Instant RESERVED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRES_AT = RESERVED_AT.plusSeconds(2);

    @Test
    void retryAfterDurableAppendReplaysTheLocalCommit() {
        var local = new CapturingLocalBudget();
        ReservationOutcomeStore journal = event -> CompletableFuture.completedFuture(
                new OutcomeChosen(event, false)
        );
        var processor = new DefaultReservationOutcomeProcessor(codec(), journal, local);

        var result = processor.process(new AuctionNotice(
                "ssp-1", NoticeKind.BILLING, "token", RESERVED_AT.plusSeconds(1)
        )).toCompletableFuture().join();

        assertEquals(DUPLICATE, ((NoticeProcessed) result).status());
        assertNotNull(local.committed);
    }

    @Test
    void expirationReplaysTheCanonicalBillingWhenBillingAlreadyWon() {
        var local = new CapturingLocalBudget();
        ReservationOutcomeStore journal = event -> {
            var billing = new MonetaryNoticeEvent(
                    event.reservationId() + ":" + BILLING,
                    BILLING,
                    event.reservationId(),
                    event.leaseId(),
                    event.campaignId(),
                    event.impressionAmountMicros(),
                    event.reservationExpiresAt(),
                    event.reservationExpiresAt().minusMillis(1)
            );
            return CompletableFuture.completedFuture(new OutcomeConflict(billing, event.kind()));
        };
        var service = new ReservationExpirationService(journal, local);
        var reference = new ReservationReference("campaign-1", leaseId(), "reservation-1");

        boolean cleanOutcome = service.expire(new ReservationExpiration(
                reference, 1_000, EXPIRES_AT
        )).toCompletableFuture().join();

        assertEquals(false, cleanOutcome);
        assertNotNull(local.committed);
        assertEquals(null, local.expired);
    }

    @Test
    void expirationWorkerRetriesTheSameMarkerAfterStorageFailure() throws Exception {
        var local = new CapturingLocalBudget();
        AtomicInteger attempts = new AtomicInteger();
        ReservationOutcomeStore journal = event -> attempts.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new IllegalStateException("store unavailable"))
                : CompletableFuture.completedFuture(new OutcomeChosen(event, true));
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

    private static final class CapturingLocalBudget implements LocalSpendingAuthority {
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
