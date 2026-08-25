package com.bbororo.rtb.dsp.proof.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.IssueReservationNotices;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.NoticesIssued;
import com.bbororo.rtb.dsp.proof.api.NoticeVerificationMessages.NoticeToken;
import com.bbororo.rtb.dsp.proof.api.NoticeVerificationMessages.VerifiedReservationNotice;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationGranted;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProofComponentFactoryTest {

    @Test
    void activeKeyIssuesAndKeyRingVerifiesReservationNotices() {
        var proof = ProofComponentFactory.create(
                "key-2",
                Map.of("key-1", filled(1), "key-2", filled(2)),
                URI.create("https://dsp.example/"));
        Instant reservedAt = Instant.parse("2026-08-25T00:00:00Z");
        Instant expiresAt = reservedAt.plusSeconds(2);
        var issued = assertInstanceOf(NoticesIssued.class, proof.issuer().issue(
                new IssueReservationNotices(
                        "ssp-1",
                        "region-1",
                        new ReservationGranted(
                                "reservation-1",
                                "2d981df7-40e4-453e-b708-c23a86efca68",
                                "campaign-1",
                                "bid-1",
                                2_000,
                                reservedAt,
                                expiresAt
                        )
                )
        ));

        String token = URLDecoder.decode(
                issued.urls().billingNoticeUrl().getRawQuery().substring("token=".length()),
                StandardCharsets.UTF_8);
        var verified = assertInstanceOf(VerifiedReservationNotice.class, proof.verifier().verify(
                new NoticeToken(
                        "ssp-1", ReservationNoticeKind.BILLING, token,
                        expiresAt.minusNanos(1))));

        assertEquals("reservation-1", verified.reservationId());
        assertEquals("campaign-1", verified.campaignId());
    }

    private static byte[] filled(int value) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) value);
        return key;
    }
}
