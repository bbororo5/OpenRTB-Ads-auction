package com.bbororo.rtb.dsp.auction;

import com.bbororo.rtb.dsp.auction.AuctionMessages.BidRequestFingerprint;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Impression;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;

/** 요청 ID와 표현 순서를 제외한 현재 입찰 결정 입력을 버전형 SHA-256으로 정규화한다. */
public final class Sha256BidRequestFingerprintCalculator implements BidRequestFingerprintCalculator {

    private static final int VERSION = 1;
    private static final byte[] DOMAIN = "dsp-bid-request-fingerprint".getBytes(StandardCharsets.US_ASCII);

    @Override
    public BidRequestFingerprint calculate(BidRequest request) {
        Objects.requireNonNull(request, "request");
        byte[] canonical = canonicalBytes(request);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new BidRequestFingerprint(VERSION, HexFormat.of().formatHex(digest.digest(canonical)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", impossible);
        }
    }

    private static byte[] canonicalBytes(BidRequest request) {
        try {
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            writeBytes(output, DOMAIN);
            output.writeInt(VERSION);
            output.writeInt(request.tmaxMillis());
            output.writeInt(request.impressions().size());
            request.impressions().stream()
                    .sorted(Comparator.comparing(Impression::id))
                    .forEach(impression -> writeImpression(output, impression));
            output.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory fingerprint encoding failed", impossible);
        }
    }

    private static void writeImpression(DataOutputStream output, Impression impression) {
        try {
            writeBytes(output, impression.id().getBytes(StandardCharsets.UTF_8));
            output.writeInt(impression.width());
            output.writeInt(impression.height());
            output.writeLong(impression.bidFloorCpmMilliKrw());
            output.writeInt(impression.expirySeconds());
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory fingerprint encoding failed", impossible);
        }
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }
}
