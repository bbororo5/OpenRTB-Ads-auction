package com.bbororo.rtb.dsp.proof;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 키 선택용 공개 머리말과 nonce, 인증 암호문을 URL-safe 문자열로 묶는다. */
final class NoticeTokenEnvelope {

    static final byte FORMAT_VERSION = 1;
    static final int GCM_TAG_BYTES = 16;

    private NoticeTokenEnvelope() {
    }

    static byte[] header(String keyId) {
        byte[] keyIdBytes = keyId.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(1 + 1 + keyIdBytes.length)
                .put(FORMAT_VERSION)
                .put((byte) keyIdBytes.length)
                .put(keyIdBytes)
                .array();
    }

    static String pack(byte[] header, byte[] nonce, byte[] ciphertext) {
        byte[] envelope = ByteBuffer.allocate(header.length + nonce.length + ciphertext.length)
                .put(header)
                .put(nonce)
                .put(ciphertext)
                .array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
    }

    static ParsedEnvelope unpack(String encodedToken) {
        byte[] envelope;
        try {
            envelope = Base64.getUrlDecoder().decode(encodedToken);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("notice token is not URL-safe Base64", failure);
        }
        if (envelope.length < 2 + SecureRandomNoticeNonceSource.NONCE_BYTES + GCM_TAG_BYTES) {
            throw new IllegalArgumentException("notice token is truncated");
        }

        ByteBuffer input = ByteBuffer.wrap(envelope);
        byte version = input.get();
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported notice token version: " + version);
        }
        int keyIdLength = Byte.toUnsignedInt(input.get());
        int minimumRemaining = keyIdLength
                + SecureRandomNoticeNonceSource.NONCE_BYTES
                + GCM_TAG_BYTES;
        if (keyIdLength == 0 || input.remaining() < minimumRemaining) {
            throw new IllegalArgumentException("notice token has an invalid key id length");
        }

        byte[] keyIdBytes = new byte[keyIdLength];
        input.get(keyIdBytes);
        byte[] header = ByteBuffer.allocate(2 + keyIdLength)
                .put(version)
                .put((byte) keyIdLength)
                .put(keyIdBytes)
                .array();
        byte[] nonce = new byte[SecureRandomNoticeNonceSource.NONCE_BYTES];
        input.get(nonce);
        byte[] ciphertext = new byte[input.remaining()];
        input.get(ciphertext);
        return new ParsedEnvelope(
                new String(keyIdBytes, StandardCharsets.UTF_8),
                header,
                nonce,
                ciphertext
        );
    }

    record ParsedEnvelope(String keyId, byte[] header, byte[] nonce, byte[] ciphertext) {
    }
}
