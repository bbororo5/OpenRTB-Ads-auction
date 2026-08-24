package com.bbororo.rtb.dsp.proof.internal;

/** 정상적인 통지 주소 묶음을 만들 수 없을 때 입찰 조정자에게 전달하는 기술 실패다. */
public final class NoticeIssuanceException extends RuntimeException {

    public NoticeIssuanceException(String message) {
        super(message);
    }

    public NoticeIssuanceException(String message, Throwable cause) {
        super(message, cause);
    }
}
