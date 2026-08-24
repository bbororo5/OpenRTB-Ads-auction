package com.bbororo.rtb.dsp.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bbororo.rtb.dsp.proof.NoticeIssuanceMessages.ReservationNoticeClaims;
import com.bbororo.rtb.dsp.proof.NoticeIssuanceMessages.SealReservationNotice;
import com.bbororo.rtb.dsp.proof.NoticeIssuanceMessages.SealedReservationNotice;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoticeKind;
import java.security.GeneralSecurityException;
import java.time.Instant;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AES-256-GCM 예약 통지 증표 봉인(Sealer) 암호 계약 테스트")
class AesGcmReservationNoticeSealerTest {

    private static final byte[] AES_256_KEY_BYTES = new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32
    };
    private static final String KEY_ID = "k-2026-v1";
    private static final Instant RESERVED_AT = Instant.parse("2026-08-18T12:00:00Z");
    private static final Instant EXPIRES_AT = RESERVED_AT.plusSeconds(5);

    private NoticeTokenKey activeKey;
    private NoticeTokenKeySource keySource;

    @BeforeEach
    void setUp() {
        SecretKey secretKey = new SecretKeySpec(AES_256_KEY_BYTES, "AES");
        activeKey = new NoticeTokenKey(KEY_ID, secretKey);
        keySource = () -> activeKey;
    }

    @Test
    @DisplayName("비결정성 검증: 동일한 클레임을 연속 봉인해도 96비트 난수 Nonce로 인해 생성된 증표 문자열이 매번 다르다")
    void successiveSealsProduceDifferentTokensDueToRandomNonce() {
        var sealer = new AesGcmReservationNoticeSealer(keySource);
        var command = sampleCommand(NoticeKind.BILLING);

        SealedReservationNotice token1 = sealer.seal(command);
        SealedReservationNotice token2 = sealer.seal(command);

        assertEquals(NoticeKind.BILLING, token1.kind());
        assertEquals(NoticeKind.BILLING, token2.kind());
        assertNotEquals(token1.encodedToken(), token2.encodedToken());
    }

    @Test
    @DisplayName("결정론적 복호화 검증: 고정된 Nonce와 비밀키로 봉인된 증표는 AES-GCM 복호화 시 원본 클레임과 100% 일치한다")
    void sealedTokenCanBeDecryptedAndMatchesOriginalPlaintext() throws Exception {
        byte[] fixedNonce = new byte[]{10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21};
        var sealer = new AesGcmReservationNoticeSealer(keySource, () -> fixedNonce);
        var command = sampleCommand(NoticeKind.WIN);

        SealedReservationNotice sealed = sealer.seal(command);
        NoticeTokenEnvelope.ParsedEnvelope unpacked = NoticeTokenEnvelope.unpack(sealed.encodedToken());

        assertEquals(KEY_ID, unpacked.keyId());

        Cipher cipher = Cipher.getInstance(AesGcmReservationNoticeSealer.TRANSFORMATION);
        cipher.init(
                Cipher.DECRYPT_MODE,
                activeKey.secretKey(),
                new GCMParameterSpec(AesGcmReservationNoticeSealer.TAG_BITS, unpacked.nonce())
        );
        cipher.updateAAD(unpacked.header());
        byte[] decryptedPlaintext = cipher.doFinal(unpacked.ciphertext());

        SealReservationNotice decodedNotice = ReservationNoticeBinaryFormat.decode(decryptedPlaintext);
        assertEquals(NoticeKind.WIN, decodedNotice.kind());
        assertEquals("res-001", decodedNotice.claims().reservationId());
        assertEquals(10_000, decodedNotice.claims().impressionAmountMicros());
    }

    @Test
    @DisplayName("위변조 방어 검증: 암호문의 1비트를 조작(Bit-flipping)하면 GCM 인증 태그 불일치로 복호화가 실패한다")
    void bitFlippingInCiphertextCausesGcmAuthenticationFailure() {
        var sealer = new AesGcmReservationNoticeSealer(keySource);
        var command = sampleCommand(NoticeKind.BILLING);
        SealedReservationNotice sealed = sealer.seal(command);

        NoticeTokenEnvelope.ParsedEnvelope unpacked = NoticeTokenEnvelope.unpack(sealed.encodedToken());
        byte[] tamperedCiphertext = unpacked.ciphertext().clone();
        tamperedCiphertext[0] ^= 0x01; // flip 1 bit in ciphertext

        assertThrows(GeneralSecurityException.class, () -> {
            Cipher cipher = Cipher.getInstance(AesGcmReservationNoticeSealer.TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    activeKey.secretKey(),
                    new GCMParameterSpec(AesGcmReservationNoticeSealer.TAG_BITS, unpacked.nonce())
            );
            cipher.updateAAD(unpacked.header());
            cipher.doFinal(tamperedCiphertext);
        });
    }

    @Test
    @DisplayName("AAD 무결성 검증: 평문 헤더(버전/키ID)를 1바이트라도 조작하면 AAD 불일치로 복호화가 실패한다")
    void modifyingHeaderCausesAadMismatchAndGcmAuthenticationFailure() {
        var sealer = new AesGcmReservationNoticeSealer(keySource);
        var command = sampleCommand(NoticeKind.BILLING);
        SealedReservationNotice sealed = sealer.seal(command);

        NoticeTokenEnvelope.ParsedEnvelope unpacked = NoticeTokenEnvelope.unpack(sealed.encodedToken());
        byte[] tamperedHeader = unpacked.header().clone();
        tamperedHeader[0] = 2; // change version byte in AAD header

        assertThrows(GeneralSecurityException.class, () -> {
            Cipher cipher = Cipher.getInstance(AesGcmReservationNoticeSealer.TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    activeKey.secretKey(),
                    new GCMParameterSpec(AesGcmReservationNoticeSealer.TAG_BITS, unpacked.nonce())
            );
            cipher.updateAAD(tamperedHeader);
            cipher.doFinal(unpacked.ciphertext());
        });
    }

    @Test
    @DisplayName("규격 검증: 12바이트(96비트)가 아닌 비정상 길이의 Nonce 주입 시 봉인이 거절된다")
    void invalidNonceLengthThrowsNoticeIssuanceException() {
        byte[] invalidNonce = new byte[]{1, 2, 3}; // not 12 bytes
        var sealer = new AesGcmReservationNoticeSealer(keySource, () -> invalidNonce);
        var command = sampleCommand(NoticeKind.WIN);

        assertThrows(NoticeIssuanceException.class, () -> sealer.seal(command));
    }

    private static SealReservationNotice sampleCommand(NoticeKind kind) {
        return new SealReservationNotice(
                kind,
                new ReservationNoticeClaims(
                        "ssp-1",
                        "ap-northeast-2",
                        "res-001",
                        "lease-001",
                        "camp-001",
                        "bid-001",
                        10_000,
                        RESERVED_AT,
                        EXPIRES_AT
                )
        );
    }
}
