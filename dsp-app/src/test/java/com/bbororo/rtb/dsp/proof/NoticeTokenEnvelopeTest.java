package com.bbororo.rtb.dsp.proof;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("통지 증표 봉투(Envelope) 패킹 및 헤더 파싱 단위 테스트")
class NoticeTokenEnvelopeTest {

    private static final String KEY_ID = "key-2026-08";
    private static final byte[] NONCE = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
    private static final byte[] CIPHERTEXT = "encrypted-payload-with-16b-tag-0123456789".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("왕복성 검증: [헤더 + Nonce + 암호문]을 Base64URL로 패킹 후 언패킹하면 모든 바이트가 100% 일치한다")
    void roundTripPreservesKeyIdHeaderNonceAndCiphertext() {
        byte[] header = NoticeTokenEnvelope.header(KEY_ID);

        String packed = NoticeTokenEnvelope.pack(header, NONCE, CIPHERTEXT);
        NoticeTokenEnvelope.ParsedEnvelope unpacked = NoticeTokenEnvelope.unpack(packed);

        assertEquals(KEY_ID, unpacked.keyId());
        assertArrayEquals(header, unpacked.header());
        assertArrayEquals(NONCE, unpacked.nonce());
        assertArrayEquals(CIPHERTEXT, unpacked.ciphertext());
    }

    @Test
    @DisplayName("헤더 규격 검증: 헤더의 첫 바이트는 버전(1)이고 두 번째 바이트는 keyId의 바이트 길이이다")
    void headerMatchesSpecificationFormatVersionAndKeyId() {
        byte[] header = NoticeTokenEnvelope.header(KEY_ID);

        assertEquals(1, header[0]); // version 1
        assertEquals(KEY_ID.getBytes(StandardCharsets.UTF_8).length, Byte.toUnsignedInt(header[1]));
    }

    @Test
    @DisplayName("인코딩 검증: 유효하지 않은 Base64URL 문자열 주입 시 예외를 던진다")
    void invalidBase64ThrowsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NoticeTokenEnvelope.unpack("invalid-base64-%%@@##")
        );
    }

    @Test
    @DisplayName("길이 검증: 최소 크기(헤더+12B Nonce+16B GCM Tag) 미만의 잘린 증표는 언패킹 시 거절된다")
    void truncatedEnvelopeBelowMinimumLengthThrowsIllegalArgumentException() {
        byte[] tooShort = new byte[]{1, 1, 'k', 1, 2}; // Less than header + 12B nonce + 16B tag
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(tooShort);

        assertThrows(
                IllegalArgumentException.class,
                () -> NoticeTokenEnvelope.unpack(encoded)
        );
    }

    @Test
    @DisplayName("버전 검증: 지원하지 않는 포맷 버전(version 2)은 언패킹 시 즉시 거절된다")
    void unsupportedVersionThrowsIllegalArgumentException() {
        byte[] header = NoticeTokenEnvelope.header(KEY_ID);
        header[0] = 2; // unsupported version 2
        String packed = NoticeTokenEnvelope.pack(header, NONCE, CIPHERTEXT);

        assertThrows(
                IllegalArgumentException.class,
                () -> NoticeTokenEnvelope.unpack(packed)
        );
    }

    @Test
    @DisplayName("키 검증: keyId 길이가 0인 비정상 헤더는 언패킹 시 즉시 거절된다")
    void zeroLengthKeyIdThrowsIllegalArgumentException() {
        byte[] invalidHeader = new byte[]{1, 0}; // version 1, keyId length 0
        String packed = NoticeTokenEnvelope.pack(invalidHeader, NONCE, CIPHERTEXT);

        assertThrows(
                IllegalArgumentException.class,
                () -> NoticeTokenEnvelope.unpack(packed)
        );
    }
}
