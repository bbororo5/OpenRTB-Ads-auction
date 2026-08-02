package com.bbororo.rtb.dsp.budget;

import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationExpiration;

/** 만료 시각이 지난 예약 후보를 내구 판정 경로에 넘기는 로컬 포트다. */
@FunctionalInterface
public interface ReservationExpirationSource {

    ReservationExpiration takeDue() throws InterruptedException;
}
