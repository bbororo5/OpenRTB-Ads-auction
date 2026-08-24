package com.bbororo.rtb.dsp.spending.api;

import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationResult;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.TryReserve;

/** Bidding이 위임받은 로컬 금액에서 예약을 원자적으로 시도하는 제공 포트다. */
public interface ReservationAuthority {

    ReservationResult tryReserve(TryReserve command);
}
