package com.bbororo.rtb.ssp.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bbororo.rtb.ssp.contract.SspMessages.RenderAcceptance;
import com.bbororo.rtb.ssp.contract.SspMessages.VerifiedRender;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StoreBackedRenderClaimServiceTest {

    @Test
    void recordsOneClaimAndOneDeliveryTaskForRepeatedProofs() {
        InMemoryClaimDeliveryStore store = new InMemoryClaimDeliveryStore();
        RenderClaimService service = new StoreBackedRenderClaimService(store);
        VerifiedRender render = verifiedRender();

        assertEquals(RenderAcceptance.ACCEPTED, service.acceptRender(render));
        assertEquals(RenderAcceptance.DUPLICATE, service.acceptRender(render));
        assertEquals(1, store.recordedClaimCount());
    }

    private static VerifiedRender verifiedRender() {
        Instant issuedAt = Instant.parse("2026-07-27T00:00:00Z");
        return new VerifiedRender(
                "provider-1", "request-1", "imp-1", "auction-1/imp-1", "proof-digest",
                "project-dsp", URI.create("https://dsp.example.test/burl"), issuedAt, issuedAt.plusSeconds(2)
        );
    }
}
