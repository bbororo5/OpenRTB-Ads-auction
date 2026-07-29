package com.bbororo.rtb.ssp.renderproof;

import com.bbororo.rtb.ssp.contract.SspMessages.ProofIssuance;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderCompleted;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderProof;
import com.bbororo.rtb.ssp.contract.SspMessages.VerifiedRender;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 시험 단계에서 쓰는 자기 완결형 렌더링 증표다.
 *
 * <p>증표 본문과 HMAC을 함께 전송하므로 검증 시 저장소를 읽지 않는다. 실제 배포용 AEAD 증표는
 * 기술 기준선 단계에서 이 구현을 대체한다.</p>
 */
public final class HmacRenderProofService implements RenderProofService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Duration MAX_VALIDITY = Duration.ofSeconds(2);

    private final byte[] signingKey;

    public HmacRenderProofService(byte[] signingKey) {
        this.signingKey = signingKey.clone();
        if (this.signingKey.length == 0) {
            throw new IllegalArgumentException("signingKey must not be empty");
        }
    }

    @Override
    public RenderProof issue(ProofIssuance issuance) {
        Objects.requireNonNull(issuance);
        validateValidity(issuance.issuedAt(), issuance.expiresAt());
        byte[] payload = encode(issuance);
        return new RenderProof(base64(payload) + "." + base64(sign(payload)));
    }

    @Override
    public Optional<VerifiedRender> verify(RenderCompleted completed) {
        Objects.requireNonNull(completed);
        try {
            String[] parts = completed.renderProof().encodedValue().split("\\.", -1);
            if (parts.length != 2) {
                return Optional.empty();
            }
            byte[] payload = decode(parts[0]);
            if (!MessageDigest.isEqual(sign(payload), decode(parts[1]))) {
                return Optional.empty();
            }
            VerifiedRender render = decodeVerifiedRender(payload, completed.renderProof().encodedValue());
            if (completed.receivedAt().isAfter(render.renderExpiresAt())) {
                return Optional.empty();
            }
            return Optional.of(render);
        } catch (IllegalArgumentException | IOException | GeneralSecurityException exception) {
            return Optional.empty();
        }
    }

    private byte[] encode(ProofIssuance issuance) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF(issuance.auction().providerId());
            output.writeUTF(issuance.auction().providerRequestId());
            output.writeUTF(issuance.winner().impId());
            output.writeUTF(issuance.winner().slotAuctionKey());
            output.writeUTF(issuance.winner().dspId());
            output.writeLong(issuance.winner().cpmKrw());
            output.writeUTF(issuance.winner().burl().toString());
            output.writeLong(issuance.issuedAt().toEpochMilli());
            output.writeLong(issuance.expiresAt().toEpochMilli());
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode render proof", exception);
        }
    }

    private VerifiedRender decodeVerifiedRender(byte[] payload, String encodedProof) throws IOException, GeneralSecurityException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            String providerId = input.readUTF();
            String providerRequestId = input.readUTF();
            String impId = input.readUTF();
            String slotAuctionKey = input.readUTF();
            String dspId = input.readUTF();
            long cpmKrw = input.readLong();
            URI billingUrl = URI.create(input.readUTF());
            Instant issuedAt = Instant.ofEpochMilli(input.readLong());
            Instant expiresAt = Instant.ofEpochMilli(input.readLong());
            validateValidity(issuedAt, expiresAt);
            if (input.available() != 0) {
                throw new IOException("Unexpected render proof data");
            }
            return new VerifiedRender(
                    providerId,
                    providerRequestId,
                    impId,
                    slotAuctionKey,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(encodedProof.getBytes(StandardCharsets.UTF_8))),
                    dspId,
                    cpmKrw,
                    billingUrl,
                    issuedAt,
                    expiresAt
            );
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static void validateValidity(Instant issuedAt, Instant expiresAt) {
        Duration validity = Duration.between(issuedAt, expiresAt);
        if (validity.isNegative() || validity.isZero() || validity.compareTo(MAX_VALIDITY) > 0) {
            throw new IllegalArgumentException("Render proof validity must be between 1 ms and 2 seconds");
        }
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
