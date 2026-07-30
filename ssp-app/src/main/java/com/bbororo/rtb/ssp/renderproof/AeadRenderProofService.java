package com.bbororo.rtb.ssp.renderproof;

import com.bbororo.rtb.ssp.contract.SspMessages.ProofIssuance;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderCompleted;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderProof;
import com.bbororo.rtb.ssp.contract.SspMessages.VerifiedRender;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** 버전형 이진 페이로드를 AES-GCM으로 봉인하는 실제 렌더링 증표 구현이다. */
public final class AeadRenderProofService implements RenderProofService {

    private static final byte FORMAT_VERSION = 2;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_ENCODED_TOKEN_CHARS = 4_096;
    private static final Duration MIN_VALIDITY = Duration.ofMillis(1);
    private static final Duration MAX_VALIDITY = Duration.ofSeconds(2);

    private final String localRegion;
    private final byte activeKeyId;
    private final Map<Byte, SecretKey> keys;
    private final SecureRandom secureRandom;

    public AeadRenderProofService(String localRegion, byte activeKeyId, Map<Byte, SecretKey> keys) {
        this(localRegion, activeKeyId, keys, new SecureRandom());
    }

    AeadRenderProofService(
            String localRegion,
            byte activeKeyId,
            Map<Byte, SecretKey> keys,
            SecureRandom secureRandom
    ) {
        if (localRegion == null || localRegion.isBlank() || localRegion.length() > 64) {
            throw new IllegalArgumentException("localRegion must contain between 1 and 64 characters");
        }
        this.localRegion = localRegion;
        this.activeKeyId = activeKeyId;
        this.keys = Map.copyOf(Objects.requireNonNull(keys, "keys"));
        this.secureRandom = Objects.requireNonNull(secureRandom);
        if (!this.keys.containsKey(activeKeyId)) {
            throw new IllegalArgumentException("active render proof key is missing");
        }
        this.keys.values().forEach(AeadRenderProofService::validateAesKey);
        warmUpActiveEncryption();
    }

    private void warmUpActiveEncryption() {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            byte[] header = {FORMAT_VERSION, activeKeyId};
            cipher(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), nonce, header)
                    .doFinal(new byte[0]);
        } catch (Exception exception) {
            throw new IllegalStateException("Render proof encryption is not ready", exception);
        }
    }

    @Override
    public RenderProof issue(ProofIssuance issuance) {
        Objects.requireNonNull(issuance);
        Instant issuedAt = Instant.ofEpochMilli(issuance.issuedAt().toEpochMilli());
        Instant expiresAt = Instant.ofEpochMilli(issuance.expiresAt().toEpochMilli());
        validateValidity(issuedAt, expiresAt);
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            byte[] header = {FORMAT_VERSION, activeKeyId};
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), nonce, header);
            byte[] encrypted = cipher.doFinal(encodePayload(issuance, issuedAt, expiresAt));
            ByteBuffer token = ByteBuffer.allocate(header.length + nonce.length + encrypted.length);
            token.put(header).put(nonce).put(encrypted);
            String encodedToken = Base64.getUrlEncoder().withoutPadding().encodeToString(token.array());
            if (encodedToken.length() > MAX_ENCODED_TOKEN_CHARS) {
                throw new IllegalArgumentException("Render proof exceeds the supported size");
            }
            return new RenderProof(encodedToken);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not issue render proof", exception);
        }
    }

    @Override
    public Optional<VerifiedRender> verify(RenderCompleted completed) {
        Objects.requireNonNull(completed);
        try {
            String encodedProof = completed.renderProof().encodedValue();
            if (encodedProof.length() > MAX_ENCODED_TOKEN_CHARS) {
                return Optional.empty();
            }
            byte[] token = Base64.getUrlDecoder().decode(encodedProof);
            if (token.length <= 2 + NONCE_BYTES + TAG_BITS / 8 || token[0] != FORMAT_VERSION) {
                return Optional.empty();
            }
            SecretKey key = keys.get(token[1]);
            if (key == null) {
                return Optional.empty();
            }
            byte[] header = Arrays.copyOfRange(token, 0, 2);
            byte[] nonce = Arrays.copyOfRange(token, 2, 2 + NONCE_BYTES);
            byte[] encrypted = Arrays.copyOfRange(token, 2 + NONCE_BYTES, token.length);
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, nonce, header);
            VerifiedRender render = decodePayload(
                    cipher.doFinal(encrypted),
                    encodedProof
            );
            if (completed.receivedAt().isBefore(render.auctionIssuedAt())
                    || completed.receivedAt().isAfter(render.renderExpiresAt())) {
                return Optional.empty();
            }
            return Optional.of(render);
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private static Cipher cipher(int mode, SecretKey key, byte[] nonce, byte[] header) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(header);
        return cipher;
    }

    private byte[] encodePayload(
            ProofIssuance issuance,
            Instant issuedAt,
            Instant expiresAt
    ) throws Exception {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF(localRegion);
            output.writeUTF(issuance.auction().providerId());
            output.writeUTF(issuance.auction().providerRequestId());
            output.writeUTF(issuance.winner().impId());
            output.writeUTF(issuance.winner().slotAuctionKey());
            output.writeUTF(issuance.winner().dspId());
            output.writeLong(issuance.winner().cpmMilliKrw());
            output.writeUTF(issuance.winner().burl().toString());
            output.writeLong(issuedAt.toEpochMilli());
            output.writeLong(expiresAt.toEpochMilli());
            output.flush();
            return bytes.toByteArray();
        }
    }

    private VerifiedRender decodePayload(byte[] payload, String encodedProof) throws Exception {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            String issuerRegion = input.readUTF();
            if (!localRegion.equals(issuerRegion)) {
                throw new IllegalArgumentException("Render proof belongs to another region");
            }
            String providerId = input.readUTF();
            String providerRequestId = input.readUTF();
            String impId = input.readUTF();
            String slotAuctionKey = input.readUTF();
            String dspId = input.readUTF();
            long cpmMilliKrw = input.readLong();
            URI billingUrl = URI.create(input.readUTF());
            Instant issuedAt = Instant.ofEpochMilli(input.readLong());
            Instant expiresAt = Instant.ofEpochMilli(input.readLong());
            validateValidity(issuedAt, expiresAt);
            if (cpmMilliKrw <= 0 || input.available() != 0) {
                throw new IllegalArgumentException("Invalid render proof payload");
            }
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(encodedProof.getBytes(StandardCharsets.UTF_8)));
            return new VerifiedRender(
                    providerId, providerRequestId, impId, slotAuctionKey, digest,
                    dspId, cpmMilliKrw, billingUrl, issuedAt, expiresAt
            );
        }
    }

    private static void validateValidity(Instant issuedAt, Instant expiresAt) {
        Duration validity = Duration.between(issuedAt, expiresAt);
        if (validity.compareTo(MIN_VALIDITY) < 0 || validity.compareTo(MAX_VALIDITY) > 0) {
            throw new IllegalArgumentException("Render proof validity must be between 1 ms and 2 seconds");
        }
    }

    private static void validateAesKey(SecretKey key) {
        if (!"AES".equalsIgnoreCase(key.getAlgorithm())) {
            throw new IllegalArgumentException("Render proof keys must use AES");
        }
        byte[] encoded = key.getEncoded();
        if (encoded != null && encoded.length != 16 && encoded.length != 24 && encoded.length != 32) {
            throw new IllegalArgumentException("Render proof AES keys must contain 128, 192, or 256 bits");
        }
    }
}
