package com.bbororo.rtb.dsp.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bbororo.rtb.dsp.notification.NoticeIssuanceMessages.ReservationNoticeClaims;
import com.bbororo.rtb.dsp.notification.NoticeIssuanceMessages.SealReservationNotice;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoticeKind;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("예약 통지 증표 이진 포맷 인코딩/디코딩 단위 테스트")
class ReservationNoticeBinaryFormatTest {

    private static final Instant RESERVED_AT = Instant.parse("2026-08-18T10:00:00.123456789Z");
    private static final Instant EXPIRES_AT = RESERVED_AT.plusSeconds(5);

    @ParameterizedTest(name = "[{index}] {0} 통지 종류의 모든 클레임 필드가 원본 그대로 복원된다")
    @EnumSource(NoticeKind.class)
    @DisplayName("왕복 가역성 검증: WIN/LOSS/BILLING 모든 종류에서 나노초 시간 및 금액이 100% 일치한다")
    void roundTripPreservesAllClaimsAcrossAllNoticeKinds(NoticeKind kind) {
        var claims = new ReservationNoticeClaims(
                "ssp-alpha",
                "ap-northeast-2",
                "res-12345",
                "lease-67890",
                "camp-999",
                "bid-777",
                25_000,
                RESERVED_AT,
                EXPIRES_AT
        );
        var originalNotice = new SealReservationNotice(kind, claims);

        byte[] encoded = ReservationNoticeBinaryFormat.encode(originalNotice);
        SealReservationNotice decoded = ReservationNoticeBinaryFormat.decode(encoded);

        assertEquals(originalNotice.kind(), decoded.kind());
        assertEquals(claims.authenticatedSspId(), decoded.claims().authenticatedSspId());
        assertEquals(claims.regionId(), decoded.claims().regionId());
        assertEquals(claims.reservationId(), decoded.claims().reservationId());
        assertEquals(claims.leaseId(), decoded.claims().leaseId());
        assertEquals(claims.campaignId(), decoded.claims().campaignId());
        assertEquals(claims.bidId(), decoded.claims().bidId());
        assertEquals(claims.impressionAmountMicros(), decoded.claims().impressionAmountMicros());
        assertEquals(claims.reservedAt(), decoded.claims().reservedAt());
        assertEquals(claims.expiresAt(), decoded.claims().expiresAt());
    }

    @Test
    @DisplayName("경계값 검증: 최대 4096바이트 길이의 식별자 문자열은 정상적으로 인코딩 및 디코딩된다")
    void stringAtMaximum4096BytesIsAccepted() {
        String longId = "a".repeat(4096);
        var notice = sampleNotice(longId);

        byte[] encoded = ReservationNoticeBinaryFormat.encode(notice);
        SealReservationNotice decoded = ReservationNoticeBinaryFormat.decode(encoded);

        assertEquals(longId, decoded.claims().reservationId());
    }

    @Test
    @DisplayName("경계값 초과 검증: 4096바이트를 초과하는 문자열(4097B)은 인코딩 시 즉시 거절된다")
    void stringExceeding4096BytesIsRejectedDuringEncoding() {
        String overLimitId = "a".repeat(4097);
        var notice = sampleNotice(overLimitId);

        assertThrows(
                IllegalArgumentException.class,
                () -> ReservationNoticeBinaryFormat.encode(notice)
        );
    }

    @Test
    @DisplayName("손상 방어 검증: 중간에 바이트열이 잘린(Truncated) 페이로드는 디코딩 시 예외를 던진다")
    void truncatedPayloadThrowsIllegalArgumentException() {
        var notice = sampleNotice("res-1");
        byte[] encoded = ReservationNoticeBinaryFormat.encode(notice);

        byte[] truncated = Arrays.copyOf(encoded, encoded.length - 5);

        assertThrows(
                IllegalArgumentException.class,
                () -> ReservationNoticeBinaryFormat.decode(truncated)
        );
    }

    @Test
    @DisplayName("무결성 방어 검증: 페이로드 끝에 불필요한 후미 쓰레기 바이트가 붙어있으면 즉시 거절된다")
    void trailingBytesThrowIllegalArgumentException() {
        var notice = sampleNotice("res-1");
        byte[] encoded = ReservationNoticeBinaryFormat.encode(notice);

        byte[] withTrailing = Arrays.copyOf(encoded, encoded.length + 1);
        withTrailing[encoded.length] = 0x7F;

        assertThrows(
                IllegalArgumentException.class,
                () -> ReservationNoticeBinaryFormat.decode(withTrailing)
        );
    }

    @Test
    @DisplayName("유효성 검증: 정의되지 않은 잘못된 NoticeKind ordinal 값(99)은 디코딩 시 거절된다")
    void invalidNoticeKindOrdinalThrowsIllegalArgumentException() {
        var notice = sampleNotice("res-1");
        byte[] encoded = ReservationNoticeBinaryFormat.encode(notice);
        encoded[0] = (byte) 99; // unknown kind ordinal

        assertThrows(
                IllegalArgumentException.class,
                () -> ReservationNoticeBinaryFormat.decode(encoded)
        );
    }

    private static SealReservationNotice sampleNotice(String reservationId) {
        return new SealReservationNotice(
                NoticeKind.WIN,
                new ReservationNoticeClaims(
                        "ssp-1",
                        "ap-northeast-2",
                        reservationId,
                        "lease-1",
                        "camp-1",
                        "bid-1",
                        5_000,
                        RESERVED_AT,
                        EXPIRES_AT
                )
        );
    }
}
