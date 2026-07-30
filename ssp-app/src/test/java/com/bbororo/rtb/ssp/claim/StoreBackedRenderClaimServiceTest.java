package com.bbororo.rtb.ssp.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bbororo.rtb.ssp.contract.SspMessages.RenderAcceptance;
import com.bbororo.rtb.ssp.contract.SspMessages.VerifiedRender;
import com.bbororo.rtb.ssp.contract.SspMessages.BillingClaim;
import com.bbororo.rtb.ssp.contract.SspMessages.DeliveryLease;
import com.bbororo.rtb.ssp.contract.SspMessages.DeliveryOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.LeasedBillingDelivery;
import com.bbororo.rtb.ssp.trust.ProviderTrustSnapshot;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StoreBackedRenderClaimServiceTest {

    @Test
    void recordsOneClaimAndOneDeliveryTaskForRepeatedProofs() {
        InMemoryClaimDeliveryStore store = new InMemoryClaimDeliveryStore();
        RenderClaimService service = new StoreBackedRenderClaimService(store, trust(true));
        VerifiedRender render = verifiedRender();

        assertEquals(RenderAcceptance.ACCEPTED, service.acceptRender(render));
        assertEquals(RenderAcceptance.DUPLICATE, service.acceptRender(render));
        assertEquals(1, store.recordedClaimCount());
    }

    @Test
    void rejectsAnOtherwiseValidProofWhenTheProviderIsNoLongerActive() {
        InMemoryClaimDeliveryStore store = new InMemoryClaimDeliveryStore();
        RenderClaimService service = new StoreBackedRenderClaimService(store, trust(false));

        assertEquals(RenderAcceptance.REJECTED, service.acceptRender(verifiedRender()));
        assertEquals(0, store.recordedClaimCount());
    }

    @Test
    void exposesAnUnavailableStoreAsRetryLater() {
        RenderClaimService service = new StoreBackedRenderClaimService(
                unavailableStore(),
                trust(true)
        );

        assertEquals(RenderAcceptance.RETRY_LATER, service.acceptRender(verifiedRender()));
    }

    private static VerifiedRender verifiedRender() {
        Instant issuedAt = Instant.parse("2026-07-27T00:00:00Z");
        return new VerifiedRender(
                "provider-1", "request-1", "imp-1", "auction-1/imp-1", "a".repeat(64),
                "project-dsp", 2_000, URI.create("https://dsp.example.test/burl"),
                issuedAt, issuedAt.plusSeconds(2)
        );
    }

    private static ProviderTrustSnapshot trust(boolean active) {
        return new ProviderTrustSnapshot() {
            @Override
            public long version() {
                return 1;
            }

            @Override
            public boolean permits(String providerId, String keyId) {
                return active;
            }

            @Override
            public boolean isActive(String providerId) {
                return active;
            }
        };
    }

    private static ClaimDeliveryStore unavailableStore() {
        return new ClaimDeliveryStore() {
            @Override
            public RenderAcceptance recordClaimAndScheduleDelivery(BillingClaim claim) {
                return RenderAcceptance.RETRY_LATER;
            }

            @Override
            public Optional<LeasedBillingDelivery> leaseDueDelivery(Instant now) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void completeOrReleaseDelivery(
                    DeliveryLease lease,
                    DeliveryOutcome outcome,
                    Instant now
            ) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
