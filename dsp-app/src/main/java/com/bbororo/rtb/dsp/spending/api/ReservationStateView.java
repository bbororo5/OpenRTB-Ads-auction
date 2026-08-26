package com.bbororo.rtb.dsp.spending.api;

import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationReference;

/** 예약 만료 작업자가 이미 종결된 로컬 예약을 다시 저널링하지 않게 하는 조회 포트다. */
public interface ReservationStateView {

    boolean isPending(ReservationReference reservation);
}
