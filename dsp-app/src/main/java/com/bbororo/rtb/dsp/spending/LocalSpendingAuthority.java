package com.bbororo.rtb.dsp.spending;

import com.bbororo.rtb.dsp.spending.SpendingMessages.CommitReservation;
import com.bbororo.rtb.dsp.spending.SpendingMessages.ExpireReservation;
import com.bbororo.rtb.dsp.spending.SpendingMessages.InstallLease;
import com.bbororo.rtb.dsp.spending.SpendingMessages.LeaseInstallResult;
import com.bbororo.rtb.dsp.spending.SpendingMessages.ReleaseReservation;
import com.bbororo.rtb.dsp.spending.SpendingMessages.ReservationFinalization;
import com.bbororo.rtb.dsp.spending.SpendingMessages.ReservationResult;
import com.bbororo.rtb.dsp.spending.SpendingMessages.TryReserve;

/** 한 DSP 인스턴스의 리스 액면과 모든 예약 상태 변경을 단독 소유한다. */
public interface LocalSpendingAuthority extends CampaignPacingView, LocalLeaseSupplyView {

    ReservationResult tryReserve(TryReserve command);

    ReservationFinalization release(ReleaseReservation command);

    ReservationFinalization commit(CommitReservation command);

    ReservationFinalization expire(ExpireReservation command);

    LeaseInstallResult install(InstallLease command, long requestStartedNanos);
}
