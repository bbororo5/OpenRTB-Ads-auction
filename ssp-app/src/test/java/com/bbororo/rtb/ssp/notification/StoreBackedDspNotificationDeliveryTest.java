package com.bbororo.rtb.ssp.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bbororo.rtb.ssp.claim.InMemoryClaimDeliveryStore;
import com.bbororo.rtb.ssp.contract.SspMessages.BillingClaim;
import com.bbororo.rtb.ssp.contract.SspMessages.DeliveryOutcome;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StoreBackedDspNotificationDeliveryTest {

    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");
    private static final URI BURL = URI.create("https://project-dsp.test/burl/reservation-1");

    @Test
    void completesTheLeasedTaskAfterSuccessfulDelivery() {
        InMemoryClaimDeliveryStore store = storeWithClaim();
        List<URI> delivered = new ArrayList<>();
        var delivery = delivery(store, (url, timeout) -> {
            delivered.add(url);
            return DeliveryOutcome.DELIVERED;
        });

        delivery.deliverDueBilling(NOW);

        assertEquals(List.of(BURL), delivered);
        assertEquals(0, store.pendingDeliveryCount());
    }

    @Test
    void releasesATransientFailureForTheNextWorkerAttempt() {
        InMemoryClaimDeliveryStore store = storeWithClaim();
        AtomicInteger attempts = new AtomicInteger();
        var delivery = delivery(store, (ignored, timeout) ->
                attempts.incrementAndGet() == 1 ? DeliveryOutcome.RETRY : DeliveryOutcome.DELIVERED
        );

        delivery.deliverDueBilling(NOW);
        assertEquals(1, store.pendingDeliveryCount());

        delivery.deliverDueBilling(NOW.plusMillis(100));
        assertEquals(2, attempts.get());
        assertEquals(0, store.pendingDeliveryCount());
    }

    @Test
    void treatsAThrownClientFailureAsRetryable() {
        InMemoryClaimDeliveryStore store = storeWithClaim();
        var delivery = delivery(store, (ignored, timeout) -> {
            throw new IllegalStateException("temporary network failure");
        });

        delivery.deliverDueBilling(NOW);

        assertEquals(1, store.pendingDeliveryCount());
    }

    @Test
    void limitsTheAttemptToTheRemainingDeadlineAndUsesItsCompletionTime() {
        InMemoryClaimDeliveryStore store = storeWithClaim();
        MutableClock clock = new MutableClock(NOW.plusMillis(4_700));
        AtomicReference<Duration> observedTimeout = new AtomicReference<>();
        var delivery = new StoreBackedDspNotificationDelivery(
                store,
                (url, timeout) -> {
                    observedTimeout.set(timeout);
                    clock.set(NOW.plusMillis(5_050));
                    return DeliveryOutcome.RETRY;
                },
                clock,
                Duration.ofMillis(500)
        );

        delivery.deliverDueBilling(NOW.plusMillis(4_700));

        assertEquals(Duration.ofMillis(300), observedTimeout.get());
        assertEquals(0, store.pendingDeliveryCount());
    }

    private static InMemoryClaimDeliveryStore storeWithClaim() {
        InMemoryClaimDeliveryStore store = new InMemoryClaimDeliveryStore();
        store.recordClaimAndScheduleDelivery(new BillingClaim(
                "provider-1", "request-1", "imp-1", "auction-1/imp-1", "a".repeat(64),
                "project-dsp", 2_000, BURL, NOW.plusSeconds(5)
        ));
        return store;
    }

    private static StoreBackedDspNotificationDelivery delivery(
            InMemoryClaimDeliveryStore store,
            DspNoticeClient client
    ) {
        return new StoreBackedDspNotificationDelivery(
                store,
                client,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(1)
        );
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now;

        private MutableClock(Instant initialTime) {
            now = new AtomicReference<>(initialTime);
        }

        private void set(Instant value) {
            now.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
