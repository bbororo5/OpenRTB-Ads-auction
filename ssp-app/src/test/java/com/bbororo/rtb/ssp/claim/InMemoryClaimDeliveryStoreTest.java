package com.bbororo.rtb.ssp.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.ssp.contract.SspMessages.BillingClaim;
import com.bbororo.rtb.ssp.contract.SspMessages.DeliveryOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderAcceptance;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryClaimDeliveryStoreTest {

    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");

    @Test
    void leasesAndCompletesOnePendingDelivery() {
        InMemoryClaimDeliveryStore store = new InMemoryClaimDeliveryStore(Duration.ofSeconds(1));
        store.recordClaimAndScheduleDelivery(claim("proof-1", NOW.plusSeconds(5)));

        var delivery = store.leaseDueDelivery(NOW).orElseThrow();
        store.completeOrReleaseDelivery(delivery.lease(), DeliveryOutcome.DELIVERED, NOW.plusMillis(10));

        assertEquals(0, store.pendingDeliveryCount());
        assertTrue(store.leaseDueDelivery(NOW.plusMillis(20)).isEmpty());
    }

    @Test
    void retriesWithANewerLeaseAndIgnoresTheOldWorkerResult() {
        InMemoryClaimDeliveryStore store = new InMemoryClaimDeliveryStore(Duration.ofMillis(100));
        store.recordClaimAndScheduleDelivery(claim("proof-1", NOW.plusSeconds(5)));
        var first = store.leaseDueDelivery(NOW).orElseThrow();

        var second = store.leaseDueDelivery(NOW.plusMillis(100)).orElseThrow();
        store.completeOrReleaseDelivery(first.lease(), DeliveryOutcome.DELIVERED, NOW.plusMillis(110));

        assertEquals(1, store.pendingDeliveryCount());
        store.completeOrReleaseDelivery(second.lease(), DeliveryOutcome.DELIVERED, NOW.plusMillis(120));
        assertEquals(0, store.pendingDeliveryCount());
    }

    @Test
    void doesNotLeaseAClaimAfterItsBillingDeadline() {
        InMemoryClaimDeliveryStore store = new InMemoryClaimDeliveryStore();
        store.recordClaimAndScheduleDelivery(claim("proof-1", NOW.plusSeconds(5)));

        assertTrue(store.leaseDueDelivery(NOW.plusSeconds(5)).isEmpty());
        assertEquals(0, store.pendingDeliveryCount());
    }

    @Test
    void recordsTheSameProofOnlyOnce() {
        InMemoryClaimDeliveryStore store = new InMemoryClaimDeliveryStore();
        BillingClaim claim = claim("proof-1", NOW.plusSeconds(5));

        assertEquals(RenderAcceptance.ACCEPTED, store.recordClaimAndScheduleDelivery(claim));
        assertEquals(RenderAcceptance.DUPLICATE, store.recordClaimAndScheduleDelivery(claim));
        assertEquals(1, store.recordedClaimCount());
        assertEquals(1, store.pendingDeliveryCount());
    }

    @Test
    void rejectsADifferentProofForTheSameAuctionSlot() {
        InMemoryClaimDeliveryStore store = new InMemoryClaimDeliveryStore();

        assertEquals(
                RenderAcceptance.ACCEPTED,
                store.recordClaimAndScheduleDelivery(claim("proof-1", NOW.plusSeconds(5)))
        );
        assertEquals(
                RenderAcceptance.REJECTED,
                store.recordClaimAndScheduleDelivery(claim("proof-2", NOW.plusSeconds(5)))
        );
        assertEquals(1, store.recordedClaimCount());
    }

    private static BillingClaim claim(String proofDigest, Instant deadline) {
        return new BillingClaim(
                "provider-1", "request-1", "imp-1", "auction-1/imp-1", proofDigest,
                "project-dsp", 2_000,
                URI.create("https://project-dsp.test/burl/reservation-1"), deadline
        );
    }
}
