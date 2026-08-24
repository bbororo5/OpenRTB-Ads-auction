package com.bbororo.rtb.dsp.proof.internal;

import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.SealReservationNotice;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.SealedReservationNotice;

/** 통지 종류와 예약 사실을 위변조할 수 없는 불투명 증표로 봉인한다. */
public interface ReservationNoticeSealer {

    SealedReservationNotice seal(SealReservationNotice command);
}
