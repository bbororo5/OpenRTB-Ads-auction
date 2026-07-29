package com.bbororo.rtb.ssp.renderproof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.contract.SspMessages.ProofIssuance;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderCompleted;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderProof;
import com.bbororo.rtb.ssp.contract.SspMessages.WinningBid;
import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class AeadRenderProofServiceTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-07-29T00:00:00Z");
    private final AeadRenderProofService service = new AeadRenderProofService(
            (byte) 7,
            Map.of((byte) 7, new SecretKeySpec(new byte[32], "AES"))
    );

    @Test
    void verifiesTheEncryptedWinningSlotAndPrice() {
        RenderProof proof = service.issue(issuance());

        var render = service.verify(new RenderCompleted(proof, ISSUED_AT.plusMillis(200))).orElseThrow();

        assertEquals("provider-1", render.providerId());
        assertEquals("imp-1", render.impId());
        assertEquals("project-dsp", render.dspId());
        assertEquals(2_000L, render.cpmKrw());
        assertEquals(URI.create("https://dsp.test/burl/1"), render.billingUrl());
    }

    @Test
    void rejectsOneBitOfCiphertextTampering() {
        RenderProof proof = service.issue(issuance());
        byte[] token = Base64.getUrlDecoder().decode(proof.encodedValue());
        token[token.length - 1] ^= 1;
        RenderProof changed = new RenderProof(Base64.getUrlEncoder().withoutPadding().encodeToString(token));

        assertFalse(service.verify(new RenderCompleted(changed, ISSUED_AT.plusMillis(200))).isPresent());
    }

    @Test
    void rejectsTheProofAfterTwoSeconds() {
        RenderProof proof = service.issue(issuance());

        assertFalse(service.verify(new RenderCompleted(proof, ISSUED_AT.plusSeconds(2).plusMillis(1))).isPresent());
    }

    private static ProofIssuance issuance() {
        AuctionRequest request = new AuctionRequest(
                "provider-1", "key-1", "request-1", 50, List.of(new AuctionSlot("imp-1", 1_000))
        );
        URI url = URI.create("https://dsp.test/burl/1");
        WinningBid winner = new WinningBid(
                "auction-1/imp-1", "imp-1", "project-dsp", "bid-1", 2_000, url, url, url
        );
        return new ProofIssuance(request, "auction-1", winner, ISSUED_AT, ISSUED_AT.plusSeconds(2));
    }
}
