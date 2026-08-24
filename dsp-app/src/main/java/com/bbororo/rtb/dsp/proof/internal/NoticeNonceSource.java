package com.bbororo.rtb.dsp.proof.internal;

/** 각 AES-GCM 증표에 사용할 새 96비트 nonce를 제공한다. */
@FunctionalInterface
public interface NoticeNonceSource {

    byte[] nextNonce();
}
