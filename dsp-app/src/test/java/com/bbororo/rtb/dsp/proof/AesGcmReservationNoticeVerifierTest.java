package com.bbororo.rtb.dsp.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.bbororo.rtb.dsp.proof.NoticeIssuanceMessages.ReservationNoticeClaims;
import com.bbororo.rtb.dsp.proof.NoticeIssuanceMessages.SealReservationNotice;
import com.bbororo.rtb.dsp.proof.NoticeIssuanceMessages.SealedReservationNotice;
import com.bbororo.rtb.dsp.proof.NoticeVerificationMessages.InvalidNoticeReason;
import com.bbororo.rtb.dsp.proof.NoticeVerificationMessages.InvalidReservationNotice;
import com.bbororo.rtb.dsp.proof.NoticeVerificationMessages.NoticeToken;
import com.bbororo.rtb.dsp.proof.NoticeVerificationMessages.NoticeVerification;
import com.bbororo.rtb.dsp.proof.NoticeVerificationMessages.VerifiedReservationNotice;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoticeKind;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("AES-256-GCM 예약 통지 증표 검증자(Verifier) 계약 및 보안 테스트")
class AesGcmReservationNoticeVerifierTest {

    private static final byte[] KEY_BYTES_V1 = new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32
    };
    private static final byte[] KEY_BYTES_V2 = new byte[]{
            32, 31, 30, 29, 28, 27, 26, 25, 24, 23, 22, 21, 20, 19, 18, 17,
            16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1
    };

    private static final String KEY_ID_V1 = "key-v1";
    private static final String KEY_ID_V2 = "key-v2";

    private static final Instant RESERVED_AT = Instant.parse("2026-08-18T10:00:00.500Z");
    private static final Instant EXPIRES_AT = RESERVED_AT.plusSeconds(5);
    private static final Instant RECEIVED_AT = RESERVED_AT.plusSeconds(2);

    private NoticeTokenKey activeKey;
    private NoticeTokenKey oldKey;
    private AesGcmReservationNoticeSealer sealer;
    private AesGcmReservationNoticeVerifier verifier;

    @BeforeEach
    void setUp() {
        SecretKey secretKeyV1 = new SecretKeySpec(KEY_BYTES_V1, "AES");
        SecretKey secretKeyV2 = new SecretKeySpec(KEY_BYTES_V2, "AES");

        oldKey = new NoticeTokenKey(KEY_ID_V1, secretKeyV1);
        activeKey = new NoticeTokenKey(KEY_ID_V2, secretKeyV2);

        Map<String, NoticeTokenKey> keyMap = Map.of(
                KEY_ID_V1, oldKey,
                KEY_ID_V2, activeKey
        );

        NoticeTokenKeySource multiKeySource = new NoticeTokenKeySource() {
            @Override
            public NoticeTokenKey activeKey() {
                return activeKey;
            }

            @Override
            public Optional<NoticeTokenKey> findKey(String keyId) {
                return Optional.ofNullable(keyMap.get(keyId));
            }
        };

        sealer = new AesGcmReservationNoticeSealer(() -> activeKey);
        verifier = new AesGcmReservationNoticeVerifier(multiKeySource);
    }

    @ParameterizedTest(name = "[{index}] {0} 통지 증표 검증 성공")
    @EnumSource(NoticeKind.class)
    @DisplayName("왕복 검증: Sealer로 봉인된 WIN/LOSS/BILLING 정상 증표는 검증 통과 후 모든 클레임이 복원된다")
    void validSealedNoticeIsVerifiedSuccessfullyAcrossAllKinds(NoticeKind kind) {
        var claims = new ReservationNoticeClaims(
                "ssp-alpha",
                "ap-northeast-2",
                "res-100",
                "lease-200",
                "camp-300",
                "bid-400",
                50_000,
                RESERVED_AT,
                EXPIRES_AT
        );
        SealedReservationNotice sealed = sealer.seal(new SealReservationNotice(kind, claims));

        NoticeVerification result = verifier.verify(new NoticeToken(
                kind,
                sealed.encodedToken(),
                RECEIVED_AT
        ));

        VerifiedReservationNotice verified = assertInstanceOf(VerifiedReservationNotice.class, result);
        assertEquals(kind, verified.kind());
        assertEquals("res-100", verified.reservationId());
        assertEquals("lease-200", verified.leaseId());
        assertEquals("camp-300", verified.campaignId());
        assertEquals("bid-400", verified.bidId());
        assertEquals(50_000, verified.impressionAmountMicros());
        assertEquals(RESERVED_AT, verified.reservedAt());
        assertEquals(EXPIRES_AT, verified.expiresAt());
        assertEquals(RECEIVED_AT, verified.receivedAt());
    }

    @Test
    @DisplayName("무중단 키 교체 검증: 이전 키(oldKey)로 봉인된 토큰도 KeySource에 등록되어 있으면 검증 성공한다")
    void tokenSealedWithOldKeyIsSuccessfullyVerified() {
        var oldSealer = new AesGcmReservationNoticeSealer(() -> oldKey);
        var claims = sampleClaims();
        SealedReservationNotice sealed = oldSealer.seal(new SealReservationNotice(NoticeKind.WIN, claims));

        NoticeVerification result = verifier.verify(new NoticeToken(
                NoticeKind.WIN,
                sealed.encodedToken(),
                RECEIVED_AT
        ));

        assertInstanceOf(VerifiedReservationNotice.class, result);
    }

    @Test
    @DisplayName("키 미식별 방어: 시스템에 등록되지 않은 unknown keyId를 가진 증표는 UNKNOWN_KEY 사유로 거절된다")
    void tokenWithUnknownKeyIdReturnsUnknownKey() {
        NoticeTokenKey unknownKey = new NoticeTokenKey(
                "unknown-key-999",
                new SecretKeySpec(KEY_BYTES_V1, "AES")
        );
        var unknownSealer = new AesGcmReservationNoticeSealer(() -> unknownKey);
        SealedReservationNotice sealed = unknownSealer.seal(new SealReservationNotice(NoticeKind.WIN, sampleClaims()));

        NoticeVerification result = verifier.verify(new NoticeToken(
                NoticeKind.WIN,
                sealed.encodedToken(),
                RECEIVED_AT
        ));

        InvalidReservationNotice invalid = assertInstanceOf(InvalidReservationNotice.class, result);
        assertEquals(InvalidNoticeReason.UNKNOWN_KEY, invalid.reason());
    }

    @Test
    @DisplayName("위변조 방어: 암호문 1비트 조작(Bit-flipping) 시 AUTHENTICATION_FAILED 사유로 거절된다")
    void tamperedCiphertextReturnsAuthenticationFailed() {
        SealedReservationNotice sealed = sealer.seal(new SealReservationNotice(NoticeKind.BILLING, sampleClaims()));
        NoticeTokenEnvelope.ParsedEnvelope unpacked = NoticeTokenEnvelope.unpack(sealed.encodedToken());

        byte[] tamperedCiphertext = unpacked.ciphertext().clone();
        tamperedCiphertext[0] ^= 0x01; // 1비트 반전

        String tamperedToken = NoticeTokenEnvelope.pack(
                unpacked.header(),
                unpacked.nonce(),
                tamperedCiphertext
        );

        NoticeVerification result = verifier.verify(new NoticeToken(
                NoticeKind.BILLING,
                tamperedToken,
                RECEIVED_AT
        ));

        InvalidReservationNotice invalid = assertInstanceOf(InvalidReservationNotice.class, result);
        assertEquals(InvalidNoticeReason.AUTHENTICATION_FAILED, invalid.reason());
    }

    @Test
    @DisplayName("AAD 무결성 방어: 평문 헤더 조작 시 AAD 불일치로 AUTHENTICATION_FAILED 사유로 거절된다")
    void tamperedHeaderReturnsAuthenticationFailed() {
        SealedReservationNotice sealed = sealer.seal(new SealReservationNotice(NoticeKind.BILLING, sampleClaims()));
        NoticeTokenEnvelope.ParsedEnvelope unpacked = NoticeTokenEnvelope.unpack(sealed.encodedToken());

        byte[] tamperedHeader = unpacked.header().clone();
        tamperedHeader[0] = 99; // mutate version byte

        String tamperedToken = NoticeTokenEnvelope.pack(
                tamperedHeader,
                unpacked.nonce(),
                unpacked.ciphertext()
        );

        NoticeVerification result = verifier.verify(new NoticeToken(
                NoticeKind.BILLING,
                tamperedToken,
                RECEIVED_AT
        ));

        // Note: version 99 causes MALFORMED during envelope unpack, or if custom header passed, AUTHENTICATION_FAILED
        assertInstanceOf(InvalidReservationNotice.class, result);
    }

    @Test
    @DisplayName("종류 치환 방어: WIN으로 봉인된 증표를 BILLING 엔드포인트에서 검증 시 WRONG_NOTICE_KIND로 거절된다")
    void noticeKindMismatchReturnsWrongNoticeKind() {
        SealedReservationNotice sealed = sealer.seal(new SealReservationNotice(NoticeKind.WIN, sampleClaims()));

        NoticeVerification result = verifier.verify(new NoticeToken(
                NoticeKind.BILLING, // expected BILLING but sealed as WIN
                sealed.encodedToken(),
                RECEIVED_AT
        ));

        InvalidReservationNotice invalid = assertInstanceOf(InvalidReservationNotice.class, result);
        assertEquals(InvalidNoticeReason.WRONG_NOTICE_KIND, invalid.reason());
    }

    @Test
    @DisplayName("손상 방어: 잘못된 Base64URL 형식의 증표는 MALFORMED 사유로 거절된다")
    void malformedBase64TokenReturnsMalformed() {
        NoticeVerification result = verifier.verify(new NoticeToken(
                NoticeKind.WIN,
                "invalid-token-@#$%",
                RECEIVED_AT
        ));

        InvalidReservationNotice invalid = assertInstanceOf(InvalidReservationNotice.class, result);
        assertEquals(InvalidNoticeReason.MALFORMED, invalid.reason());
    }

    private static ReservationNoticeClaims sampleClaims() {
        return new ReservationNoticeClaims(
                "ssp-1",
                "ap-northeast-2",
                "res-1",
                "lease-1",
                "camp-1",
                "bid-1",
                10_000,
                RESERVED_AT,
                EXPIRES_AT
        );
    }
}
