package com.bbororo.rtb.dsp.notification;

import com.bbororo.rtb.dsp.notification.NoticeVerificationMessages.NoticeToken;
import com.bbororo.rtb.dsp.notification.NoticeVerificationMessages.NoticeVerification;

/** 외부 통지의 불투명 증표를 검증해 원래 예약 사실을 복원한다. */
public interface ReservationNoticeVerifier {

    NoticeVerification verify(NoticeToken token);
}
