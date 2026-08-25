package com.bbororo.rtb.dsp.proof.api;

import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.IssueReservationNotices;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.NoticeIssuanceResult;

/** 예약 사실로부터 OpenRTB 낙찰·패찰·과금 통지 주소를 함께 발급한다. */
public interface ReservationNoticeIssuer {

    NoticeIssuanceResult issue(IssueReservationNotices command);
}
