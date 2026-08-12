package com.bbororo.rtb.dsp.notification;

import static com.bbororo.rtb.dsp.contract.ContractChecks.requireNonBlank;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** 증표 발급에 사용하는 식별 가능한 AES-256 키다. */
public record NoticeTokenKey(String keyId, SecretKey secretKey) {

    private static final int AES_256_BYTES = 32;
    private static final int MAX_KEY_ID_BYTES = 255;

    public NoticeTokenKey {
        keyId = requireNonBlank(keyId, "keyId");
        if (keyId.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_ID_BYTES) {
            throw new IllegalArgumentException("keyId must be at most 255 UTF-8 bytes");
        }
        Objects.requireNonNull(secretKey, "secretKey");
        byte[] encoded = secretKey.getEncoded();
        if (!"AES".equalsIgnoreCase(secretKey.getAlgorithm())
                || encoded == null
                || encoded.length != AES_256_BYTES) {
            throw new IllegalArgumentException("secretKey must be a 256-bit AES key");
        }
        secretKey = new SecretKeySpec(encoded.clone(), "AES");
    }
}
