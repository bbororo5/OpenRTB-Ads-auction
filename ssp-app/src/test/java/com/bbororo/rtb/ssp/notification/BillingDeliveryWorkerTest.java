package com.bbororo.rtb.ssp.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionNotice;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BillingDeliveryWorkerTest {

    @Test
    void passesTheServerClockToOneDeliveryAttempt() {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        AtomicReference<Instant> observed = new AtomicReference<>();
        var delivery = delivery(deliveryTime -> {
            observed.set(deliveryTime);
            return BillingDeliveryAttempt.empty();
        });

        try (var worker = new BillingDeliveryWorker(
                delivery,
                Clock.fixed(now, ZoneOffset.UTC),
                1
        )) {
            worker.runOnce();
        }

        assertEquals(now, observed.get());
    }

    @Test
    void staysBlockedUntilDurableWorkIsSignalled() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        var delivery = delivery(ignored -> {
            attempted.countDown();
            return BillingDeliveryAttempt.completed();
        });

        try (var worker = new BillingDeliveryWorker(delivery, Clock.systemUTC(), 1)) {
            worker.start();
            assertFalse(attempted.await(50, TimeUnit.MILLISECONDS));

            worker.signal();

            assertTrue(attempted.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void runsOnlyTheConfiguredNumberOfDeliveryAttemptsConcurrently() throws Exception {
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(8);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        var delivery = delivery(ignored -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
                completed.countDown();
            }
            return BillingDeliveryAttempt.completed();
        });

        try (var worker = new BillingDeliveryWorker(delivery, Clock.systemUTC(), 4)) {
            worker.start();
            for (int index = 0; index < 8; index++) {
                worker.signal();
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(4, maximum.get());
            release.countDown();
            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertEquals(4, maximum.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void wakesAgainAtTheRetryTimeReturnedByTheStore() throws Exception {
        CountDownLatch attempts = new CountDownLatch(2);
        AtomicInteger sequence = new AtomicInteger();
        var delivery = delivery(ignored -> {
            attempts.countDown();
            return sequence.incrementAndGet() == 1
                    ? BillingDeliveryAttempt.retryScheduled(Instant.now().plusMillis(50))
                    : BillingDeliveryAttempt.completed();
        });

        try (var worker = new BillingDeliveryWorker(delivery, Clock.systemUTC(), 1)) {
            worker.start();
            worker.signal();

            assertTrue(attempts.await(1, TimeUnit.SECONDS));
            assertEquals(2, sequence.get());
        }
    }

    private static DspNotificationDelivery delivery(Attempt attempt) {
        return new DspNotificationDelivery() {
            @Override
            public void sendAuctionNotices(List<AuctionNotice> notices) {
            }

            @Override
            public BillingDeliveryAttempt deliverDueBilling(Instant now) {
                return attempt.run(now);
            }
        };
    }

    @FunctionalInterface
    private interface Attempt {
        BillingDeliveryAttempt run(Instant now);
    }
}
