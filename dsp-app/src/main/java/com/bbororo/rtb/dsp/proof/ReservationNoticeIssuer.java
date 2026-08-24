package com.bbororo.rtb.dsp.proof;

import com.bbororo.rtb.dsp.proof.NoticeIssuanceMessages.IssueReservationNotices;
import com.bbororo.rtb.dsp.proof.NoticeIssuanceMessages.ReservationNoticeUrls;

/** 예약 사실로부터 OpenRTB 낙찰·패찰·과금 통지 주소를 함께 발급한다. */
public interface ReservationNoticeIssuer {

    ReservationNoticeUrls issue(IssueReservationNotices command);
}
