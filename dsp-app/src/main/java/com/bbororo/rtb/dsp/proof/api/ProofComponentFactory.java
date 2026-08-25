package com.bbororo.rtb.dsp.proof.api;

import com.bbororo.rtb.dsp.proof.internal.AesGcmReservationNoticeSealer;
import com.bbororo.rtb.dsp.proof.internal.AesGcmReservationNoticeVerifier;
import com.bbororo.rtb.dsp.proof.internal.DefaultNoticeUrlFactory;
import com.bbororo.rtb.dsp.proof.internal.DefaultReservationNoticeIssuer;
import com.bbororo.rtb.dsp.proof.internal.KeyRingNoticeTokenKeySource;
import com.bbororo.rtb.dsp.proof.internal.ReservationNoticeClaimsFactory;
import com.bbororo.rtb.dsp.proof.spi.NoticeTokenKey;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.crypto.spec.SecretKeySpec;

/** 키 링과 공개 URL로 Proof 발급·검증 포트를 한 번에 조립한다. */
public final class ProofComponentFactory {

    private ProofComponentFactory() {
    }

    public static Components create(
            String activeKeyId,
            Map<String, byte[]> encodedKeys,
            URI publicBaseUri
    ) {
        Objects.requireNonNull(encodedKeys, "encodedKeys");
        Map<String, NoticeTokenKey> keys = new LinkedHashMap<>();
        encodedKeys.forEach((keyId, encoded) -> keys.put(
                keyId,
                new NoticeTokenKey(
                        keyId,
                        new SecretKeySpec(
                                Objects.requireNonNull(encoded, "encoded key").clone(),
                                "AES"
                        )
                )
        ));
        var keySource = new KeyRingNoticeTokenKeySource(activeKeyId, keys);
        var issuer = new DefaultReservationNoticeIssuer(
                new ReservationNoticeClaimsFactory(),
                new AesGcmReservationNoticeSealer(keySource),
                new DefaultNoticeUrlFactory(publicBaseUri)
        );
        return new Components(issuer, new AesGcmReservationNoticeVerifier(keySource));
    }

    public record Components(
            ReservationNoticeIssuer issuer,
            ReservationNoticeVerifier verifier
    ) {
        public Components {
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(verifier, "verifier");
        }
    }
}
