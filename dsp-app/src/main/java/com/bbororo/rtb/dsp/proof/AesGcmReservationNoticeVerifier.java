package com.bbororo.rtb.dsp.proof;

import com.bbororo.rtb.dsp.proof.NoticeIssuanceMessages.SealReservationNotice;
import com.bbororo.rtb.dsp.proof.NoticeTokenEnvelope.ParsedEnvelope;
import com.bbororo.rtb.dsp.proof.NoticeVerificationMessages.InvalidNoticeReason;
import com.bbororo.rtb.dsp.proof.NoticeVerificationMessages.InvalidReservationNotice;
import com.bbororo.rtb.dsp.proof.NoticeVerificationMessages.NoticeToken;
import com.bbororo.rtb.dsp.proof.NoticeVerificationMessages.NoticeVerification;
import com.bbororo.rtb.dsp.proof.NoticeVerificationMessages.VerifiedReservationNotice;
import java.security.GeneralSecurityException;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/** JDK AES-256-GCM으로 봉인된 외부 통지 증표를 검증하고 원래 예약 사실을 복원한다. */
public final class AesGcmReservationNoticeVerifier implements ReservationNoticeVerifier {

    private static final ThreadLocal<Cipher> CIPHER_CACHE = ThreadLocal.withInitial(() -> {
        try {
            return Cipher.getInstance(AesGcmReservationNoticeSealer.TRANSFORMATION);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Failed to initialize AES-GCM Cipher instance", failure);
        }
    });

    private final NoticeTokenKeySource keySource;

    public AesGcmReservationNoticeVerifier(NoticeTokenKeySource keySource) {
        this.keySource = Objects.requireNonNull(keySource, "keySource");
    }

    @Override
    public NoticeVerification verify(NoticeToken token) {
        Objects.requireNonNull(token, "token");

        ParsedEnvelope envelope;
        try {
            envelope = NoticeTokenEnvelope.unpack(token.encodedValue());
        } catch (IllegalArgumentException failure) {
            return new InvalidReservationNotice(InvalidNoticeReason.MALFORMED);
        }

        var keyOptional = keySource.findKey(envelope.keyId());
        if (keyOptional.isEmpty()) {
            return new InvalidReservationNotice(InvalidNoticeReason.UNKNOWN_KEY);
        }
        NoticeTokenKey key = keyOptional.get();

        byte[] plaintext;
        try {
            Cipher cipher = CIPHER_CACHE.get();
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key.secretKey(),
                    new GCMParameterSpec(AesGcmReservationNoticeSealer.TAG_BITS, envelope.nonce())
            );
            cipher.updateAAD(envelope.header());
            plaintext = cipher.doFinal(envelope.ciphertext());
        } catch (GeneralSecurityException failure) {
            return new InvalidReservationNotice(InvalidNoticeReason.AUTHENTICATION_FAILED);
        }

        SealReservationNotice notice;
        try {
            notice = ReservationNoticeBinaryFormat.decode(plaintext);
        } catch (IllegalArgumentException failure) {
            return new InvalidReservationNotice(InvalidNoticeReason.MALFORMED);
        }

        if (notice.kind() != token.kind()) {
            return new InvalidReservationNotice(InvalidNoticeReason.WRONG_NOTICE_KIND);
        }

        var claims = notice.claims();
        return new VerifiedReservationNotice(
                notice.kind(),
                claims.reservationId(),
                claims.leaseId(),
                claims.campaignId(),
                claims.bidId(),
                claims.impressionAmountMicros(),
                claims.reservedAt(),
                claims.expiresAt(),
                token.receivedAt()
        );
    }
}
