package com.bbororo.rtb.ssp.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bbororo.rtb.ssp.claim.InMemoryClaimDeliveryStore;
import com.bbororo.rtb.ssp.contract.SspMessages.BillingClaim;
import com.bbororo.rtb.ssp.contract.SspMessages.DeliveryOutcome;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StoreBackedDspNotificationDeliveryTest {

    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");
    private static final URI BURL = URI.create("https://project-dsp.test/burl/reservation-1");

    @Test
    void completesTheLeasedTaskAfterSuccessfulDelivery() {
        InMemoryClaimDeliveryStore store = storeWithClaim();
        List<URI> delivered = new ArrayList<>();
        var delivery = new StoreBackedDspNotificationDelivery(store, url -> {
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
        var delivery = new StoreBackedDspNotificationDelivery(store, ignored ->
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
        var delivery = new StoreBackedDspNotificationDelivery(store, ignored -> {
            throw new IllegalStateException("temporary network failure");
        });

        delivery.deliverDueBilling(NOW);

        assertEquals(1, store.pendingDeliveryCount());
    }

    private static InMemoryClaimDeliveryStore storeWithClaim() {
        InMemoryClaimDeliveryStore store = new InMemoryClaimDeliveryStore();
        store.recordClaimAndScheduleDelivery(new BillingClaim(
                "provider-1", "request-1", "imp-1", "auction-1/imp-1", "a".repeat(64),
                "project-dsp", 2_000, BURL, NOW.plusSeconds(5)
        ));
        return store;
    }
}
