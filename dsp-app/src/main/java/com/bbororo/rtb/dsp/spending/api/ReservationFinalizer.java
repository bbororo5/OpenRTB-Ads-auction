package com.bbororo.rtb.dsp.spending.api;

import com.bbororo.rtb.dsp.spending.api.SpendingMessages.CommitReservation;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ExpireReservation;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReleaseReservation;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationFinalization;

/** Outcome이 canonical 결과를 로컬 예약에 멱등 반영하는 제공 포트다. */
public interface ReservationFinalizer {

    ReservationFinalization release(ReleaseReservation command);

    ReservationFinalization commit(CommitReservation command);

    ReservationFinalization expire(ExpireReservation command);
}
