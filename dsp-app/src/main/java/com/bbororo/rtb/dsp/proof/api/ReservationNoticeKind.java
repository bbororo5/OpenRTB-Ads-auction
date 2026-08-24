package com.bbororo.rtb.dsp.proof.api;

/** 예약 증표가 인증하는 RTB 통지의 의미다. 전송 프로토콜의 열거형과 독립적이다. */
public enum ReservationNoticeKind {
    WIN,
    LOSS,
    BILLING
}
