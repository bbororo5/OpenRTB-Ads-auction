package com.bbororo.rtb.ssp.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BillingDeliveryWorkerTest {

    @Test
    void passesTheServerClockToOneDeliveryAttempt() {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        AtomicReference<Instant> observed = new AtomicReference<>();
        DspNotificationDelivery delivery = new DspNotificationDelivery() {
            @Override
            public void sendAuctionNotices(List<com.bbororo.rtb.ssp.contract.SspMessages.AuctionNotice> notices) {
            }

            @Override
            public BillingDeliveryAttempt deliverDueBilling(Instant deliveryTime) {
                observed.set(deliveryTime);
                return BillingDeliveryAttempt.empty();
            }
        };

        try (var worker = new BillingDeliveryWorker(
                delivery,
                Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofMillis(10)
        )) {
            worker.runOnce();
        }

        assertEquals(now, observed.get());
    }

    @Test
    void runsOnlyTheConfiguredNumberOfDeliveryAttemptsConcurrently() throws Exception {
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        DspNotificationDelivery delivery = new DspNotificationDelivery() {
            @Override
            public void sendAuctionNotices(List<com.bbororo.rtb.ssp.contract.SspMessages.AuctionNotice> notices) {
            }

            @Override
            public BillingDeliveryAttempt deliverDueBilling(Instant deliveryTime) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return BillingDeliveryAttempt.empty();
            }
        };

        try (var worker = new BillingDeliveryWorker(
                delivery,
                Clock.systemUTC(),
                Duration.ofSeconds(1),
                4
        )) {
            worker.start();
            assertTrue(entered.await(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }
}
