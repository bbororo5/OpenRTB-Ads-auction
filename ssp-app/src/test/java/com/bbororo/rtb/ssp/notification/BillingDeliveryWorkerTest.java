package com.bbororo.rtb.ssp.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
            public void deliverDueBilling(Instant deliveryTime) {
                observed.set(deliveryTime);
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
}
