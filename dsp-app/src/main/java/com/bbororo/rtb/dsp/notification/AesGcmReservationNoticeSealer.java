package com.bbororo.rtb.dsp.notification;

import com.bbororo.rtb.dsp.notification.NoticeIssuanceMessages.SealReservationNotice;
import com.bbororo.rtb.dsp.notification.NoticeIssuanceMessages.SealedReservationNotice;
import java.security.GeneralSecurityException;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/** JDK AES-256-GCM으로 통지 종류와 예약 사실을 함께 인증 암호화한다. */
public final class AesGcmReservationNoticeSealer implements ReservationNoticeSealer {

    static final String TRANSFORMATION = "AES/GCM/NoPadding";
    static final int TAG_BITS = 128;

    private final NoticeTokenKeySource keySource;
    private final NoticeNonceSource nonceSource;

    public AesGcmReservationNoticeSealer(NoticeTokenKeySource keySource) {
        this(keySource, new SecureRandomNoticeNonceSource());
    }

    AesGcmReservationNoticeSealer(
            NoticeTokenKeySource keySource,
            NoticeNonceSource nonceSource
    ) {
        this.keySource = Objects.requireNonNull(keySource, "keySource");
        this.nonceSource = Objects.requireNonNull(nonceSource, "nonceSource");
    }

    @Override
    public SealedReservationNotice seal(SealReservationNotice command) {
        Objects.requireNonNull(command, "command");
        NoticeTokenKey activeKey = Objects.requireNonNull(
                keySource.activeKey(),
                "active notice token key"
        );
        byte[] nonce = Objects.requireNonNull(nonceSource.nextNonce(), "nonce");
        if (nonce.length != SecureRandomNoticeNonceSource.NONCE_BYTES) {
            throw new NoticeIssuanceException("AES-GCM nonce must be 12 bytes");
        }

        byte[] header = NoticeTokenEnvelope.header(activeKey.keyId());
        byte[] plaintext = ReservationNoticeBinaryFormat.encode(command);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    activeKey.secretKey(),
                    new GCMParameterSpec(TAG_BITS, nonce)
            );
            cipher.updateAAD(header);
            byte[] ciphertext = cipher.doFinal(plaintext);
            return new SealedReservationNotice(
                    command.kind(),
                    NoticeTokenEnvelope.pack(header, nonce, ciphertext)
            );
        } catch (GeneralSecurityException failure) {
            throw new NoticeIssuanceException("failed to seal reservation notice", failure);
        }
    }
}
