package com.bbororo.rtb.ssp.renderproof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            "region-a",
            (byte) 7,
            Map.of((byte) 7, new SecretKeySpec(new byte[32], "AES"))
    );

    @Test
    void verifiesTheEncryptedWinningSlotAndPrice() {
        RenderProof proof = service.issue(issuance());

        var render = service.verify(new RenderCompleted(proof, ISSUED_AT.plusMillis(200))).orElseThrow();

        assertEquals("provider-1", render.providerId());
        assertEquals("request-1", render.providerRequestId());
        assertEquals("imp-1", render.impId());
        assertEquals("auction-1/imp-1", render.slotAuctionKey());
        assertEquals("project-dsp", render.dspId());
        assertEquals(2_000L, render.cpmMilliKrw());
        assertEquals("auction-1", render.auctionId());
        assertEquals("https://dsp.test/burl/1", render.billingUrlTemplate().value());
        assertEquals(ISSUED_AT.plusMillis(200), render.impressionAt());
        assertTrue(render.proofDigest().matches("[0-9a-f]{64}"));
    }

    @Test
    void givesTheSameVerifiedIdentityForARepeatedProof() {
        RenderProof proof = service.issue(issuance());
        RenderCompleted completed = new RenderCompleted(proof, ISSUED_AT.plusMillis(200));

        var first = service.verify(completed).orElseThrow();
        var repeated = service.verify(completed).orElseThrow();

        assertEquals(first, repeated);
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
    void rejectsAnUnknownProofFormatVersion() {
        RenderProof proof = service.issue(issuance());
        byte[] token = Base64.getUrlDecoder().decode(proof.encodedValue());
        token[0] = 99;
        RenderProof changed = new RenderProof(Base64.getUrlEncoder().withoutPadding().encodeToString(token));

        assertFalse(service.verify(new RenderCompleted(changed, ISSUED_AT.plusMillis(200))).isPresent());
    }

    @Test
    void rejectsTheProofAfterTwoSeconds() {
        RenderProof proof = service.issue(issuance());

        assertFalse(service.verify(new RenderCompleted(proof, ISSUED_AT.plusSeconds(2).plusMillis(1))).isPresent());
    }

    @Test
    void rejectsTheProofBeforeItsIssuanceTime() {
        RenderProof proof = service.issue(issuance());

        assertFalse(service.verify(new RenderCompleted(proof, ISSUED_AT.minusMillis(1))).isPresent());
    }

    @Test
    void acceptsTheProofAtTheExactTwoSecondBoundary() {
        RenderProof proof = service.issue(issuance());

        assertTrue(service.verify(
                new RenderCompleted(proof, ISSUED_AT.plusSeconds(2))
        ).isPresent());
    }

    @Test
    void rejectsAValidityOutsideTheOneMillisecondToTwoSecondContract() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.issue(issuance(ISSUED_AT.plusNanos(500_000)))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> service.issue(issuance(ISSUED_AT.plusSeconds(2).plusMillis(1)))
        );
    }

    @Test
    void rejectsAnOversizedProofBeforeBase64Decoding() {
        RenderProof oversized = new RenderProof("a".repeat(4_097));

        assertFalse(service.verify(
                new RenderCompleted(oversized, ISSUED_AT.plusMillis(200))
        ).isPresent());
    }

    @Test
    void verifiesAnOldKeyDuringKeyRotation() {
        SecretKeySpec oldKey = new SecretKeySpec(new byte[32], "AES");
        byte[] newKeyBytes = new byte[32];
        newKeyBytes[0] = 1;
        SecretKeySpec newKey = new SecretKeySpec(newKeyBytes, "AES");
        RenderProof proof = new AeadRenderProofService(
                "region-a", (byte) 7, Map.of((byte) 7, oldKey)
        ).issue(issuance());
        var rotated = new AeadRenderProofService(
                "region-a", (byte) 8, Map.of((byte) 7, oldKey, (byte) 8, newKey)
        );

        assertTrue(rotated.verify(
                new RenderCompleted(proof, ISSUED_AT.plusMillis(200))
        ).isPresent());
    }

    @Test
    void rejectsAnInvalidAesKeyAtStartup() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AeadRenderProofService(
                        "region-a",
                        (byte) 7,
                        Map.of((byte) 7, new SecretKeySpec(new byte[10], "AES"))
                )
        );
    }

    @Test
    void rejectsAProofIssuedByAnotherRegion() {
        RenderProof proof = service.issue(issuance());
        var anotherRegion = new AeadRenderProofService(
                "region-b",
                (byte) 7,
                Map.of((byte) 7, new SecretKeySpec(new byte[32], "AES"))
        );

        assertFalse(anotherRegion.verify(
                new RenderCompleted(proof, ISSUED_AT.plusMillis(200))
        ).isPresent());
    }

    private static ProofIssuance issuance() {
        return issuance(ISSUED_AT.plusSeconds(2));
    }

    private static ProofIssuance issuance(Instant expiresAt) {
        AuctionRequest request = new AuctionRequest(
                "provider-1", "key-1", "request-1", 50,
                List.of(new AuctionSlot("imp-1", 300, 250, 1_000))
        );
        URI url = URI.create("https://dsp.test/burl/1");
        WinningBid winner = new WinningBid(
                "auction-1/imp-1", "imp-1", "project-dsp", "bid-1", 2_000, url, url, url
        );
        return new ProofIssuance(request, "auction-1", winner, ISSUED_AT, expiresAt);
    }
}
