package com.bbororo.rtb.dsp.notification;

import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationGranted;
import com.bbororo.rtb.dsp.notification.NotificationMessages.NoticeToken;
import com.bbororo.rtb.dsp.notification.NotificationMessages.NoticeVerification;
import com.bbororo.rtb.dsp.notification.NotificationMessages.NotificationUrls;

/** 예약 신원을 외부가 해석할 수 없는 인증된 nurl·lurl·burl로 바꾼다. */
public interface ReservationNoticeCodec {

    NotificationUrls issue(ReservationGranted reservation);

    NoticeVerification verify(NoticeToken token);
}
