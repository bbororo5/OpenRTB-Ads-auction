package com.bbororo.rtb.dsp.notification;

import java.security.SecureRandom;
import java.util.Objects;

/** 프로세스의 안전한 난수원으로 매 증표의 AES-GCM nonce를 생성한다. */
public final class SecureRandomNoticeNonceSource implements NoticeNonceSource {

    static final int NONCE_BYTES = 12;

    private final SecureRandom secureRandom;

    public SecureRandomNoticeNonceSource() {
        this(new SecureRandom());
    }

    SecureRandomNoticeNonceSource(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    @Override
    public byte[] nextNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        return nonce;
    }
}
