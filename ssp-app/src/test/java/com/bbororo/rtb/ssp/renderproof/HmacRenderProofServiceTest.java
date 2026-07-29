package com.bbororo.rtb.ssp.renderproof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.contract.SspMessages.ProofIssuance;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderCompleted;
import com.bbororo.rtb.ssp.contract.SspMessages.WinningBid;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class HmacRenderProofServiceTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-07-27T00:00:00Z");
    private final HmacRenderProofService service = new HmacRenderProofService("test-key".getBytes(StandardCharsets.UTF_8));

    @Test
    void verifiesTheWinningSlotAndBillingTargetWithoutAStoreLookup() {
        var proof = service.issue(issuance(ISSUED_AT.plusSeconds(2)));

        var render = service.verify(new RenderCompleted(proof, ISSUED_AT.plusMillis(10))).orElseThrow();

        assertEquals("provider-1", render.providerId());
        assertEquals("request-1", render.providerRequestId());
        assertEquals("imp-1", render.impId());
        assertEquals("auction-1/imp-1", render.slotAuctionKey());
        assertEquals("project-dsp", render.dspId());
        assertEquals(2_000L, render.cpmMilliKrw());
        assertEquals(URI.create("https://dsp.example.test/burl"), render.billingUrl());
    }

    @Test
    void rejectsAChangedProof() {
        var proof = service.issue(issuance(ISSUED_AT.plusSeconds(2)));
        String changed = proof.encodedValue().substring(0, proof.encodedValue().length() - 1) + "A";

        assertFalse(service.verify(new RenderCompleted(new com.bbororo.rtb.ssp.contract.SspMessages.RenderProof(changed), ISSUED_AT)).isPresent());
    }

    @Test
    void rejectsAProofReceivedAfterItsExpiry() {
        var proof = service.issue(issuance(ISSUED_AT.plusSeconds(2)));

        assertFalse(service.verify(new RenderCompleted(proof, ISSUED_AT.plusSeconds(2).plusMillis(1))).isPresent());
    }

    @Test
    void rejectsValidityLongerThanTwoSeconds() {
        assertThrows(IllegalArgumentException.class, () -> service.issue(issuance(ISSUED_AT.plusSeconds(3))));
    }

    private static ProofIssuance issuance(Instant expiresAt) {
        AuctionRequest request = new AuctionRequest(
                "provider-1", "key-1", "request-1", 50, List.of(new AuctionSlot("imp-1", 1_000))
        );
        WinningBid winner = new WinningBid(
                "auction-1/imp-1", "imp-1", "project-dsp", "bid-1", 2_000,
                URI.create("https://dsp.example.test/nurl"),
                URI.create("https://dsp.example.test/lurl"),
                URI.create("https://dsp.example.test/burl")
        );
        return new ProofIssuance(request, "auction-1", winner, ISSUED_AT, expiresAt);
    }
}
