package com.bbororo.rtb.ssp.e2e;

import com.bbororo.rtb.ssp.api.AuctionRenderApi;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionResult;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderAcceptance;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderCompleted;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderProof;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * SSP의 첫 수직 인수 시나리오다.
 *
 * <p>현재는 구현체가 없으므로 의도적으로 실패한다. 이후 실제 SSP 조립체가
 * {@link SspE2eFixture#start()}를 제공하면 이 시나리오를 그대로 통과시킨다.</p>
 */
class SspAuctionBillingE2eTest {

    @Test
    void trusted_provider_auction_then_render_claim_and_burl_delivery() {
        SspE2eFixture fixture = SspE2eFixture.start();
        AuctionRenderApi ssp = fixture.api();

        AuctionResult result = ssp.auction(new AuctionRequest(
                "provider-a",
                "key-2026-01",
                "request-1",
                "request-fingerprint-1",
                Instant.parse("2026-07-24T00:00:00Z").plusMillis(50),
                List.of(new AuctionSlot("imp-1"))
        ));

        assertEquals(1, result.winners().size());
        assertEquals("project-dsp", result.winners().getFirst().dspId());
        assertEquals(2_000L, result.winners().getFirst().cpmKrw());
        assertFalse(result.renderProof().encodedValue().isBlank());

        RenderAcceptance acceptance = ssp.completeRender(new RenderCompleted(
                new RenderProof(result.renderProof().encodedValue()),
                Instant.parse("2026-07-24T00:00:00Z").plusMillis(200)
        ));

        assertEquals(RenderAcceptance.ACCEPTED, acceptance);
        assertEquals(1, fixture.persistedClaimCount());
        assertEquals(1, fixture.pendingBillingDeliveryCount());

        fixture.deliverDueBilling(Instant.parse("2026-07-24T00:00:00Z").plusMillis(250));

        assertEquals(List.of(URI.create("https://project-dsp.test/burl/reservation-1")), fixture.deliveredBillingUrls());
        assertEquals(0, fixture.pendingBillingDeliveryCount());
    }
}
