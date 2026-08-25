package com.bbororo.rtb.dsp.bidding.internal;

import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationRejection;

/** 예약 거절의 범위를 후보·슬롯·요청 진행 결정으로 바꾼다. */
public interface CandidateContinuationPolicy {

    Continuation after(ReservationRejection rejection);

    enum Continuation {
        TRY_NEXT_CANDIDATE,
        STOP_SLOT,
        STOP_REQUEST
    }
}
