package com.bbororo.rtb.dsp.proof.api;

import com.bbororo.rtb.dsp.proof.api.NoticeVerificationMessages.NoticeToken;
import com.bbororo.rtb.dsp.proof.api.NoticeVerificationMessages.NoticeVerification;

/** 외부 통지의 불투명 증표를 검증해 원래 예약 사실을 복원한다. */
public interface ReservationNoticeVerifier {

    NoticeVerification verify(NoticeToken token);
}
