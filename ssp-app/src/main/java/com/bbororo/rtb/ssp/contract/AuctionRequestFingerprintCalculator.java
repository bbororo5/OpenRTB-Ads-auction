package com.bbororo.rtb.ssp.contract;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** {@link AuctionRequest}의 내부 중복 판단 지문을 계산한다. */
final class AuctionRequestFingerprintCalculator {

    private AuctionRequestFingerprintCalculator() {
    }

    static AuctionRequestFingerprint calculate(AuctionRequest request) {
        MessageDigest digest = sha256();

        update(digest, request.providerId());
        update(digest, request.providerRequestId());
        update(digest, request.deadline().toString());
        update(digest, Integer.toString(request.slots().size()));
        request.slots().forEach(slot -> update(digest, slot.impId()));

        return new AuctionRequestFingerprint(HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
