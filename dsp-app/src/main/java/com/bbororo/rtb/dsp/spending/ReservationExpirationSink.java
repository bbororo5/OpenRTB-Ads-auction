package com.bbororo.rtb.dsp.spending;

import com.bbororo.rtb.dsp.spending.SpendingMessages.ReservationExpiration;

/** 예약 성공과 함께 만료 후보를 잃지 않도록 등록하는 로컬 포트다. */
@FunctionalInterface
public interface ReservationExpirationSink {

    void schedule(ReservationExpiration expiration);
}
