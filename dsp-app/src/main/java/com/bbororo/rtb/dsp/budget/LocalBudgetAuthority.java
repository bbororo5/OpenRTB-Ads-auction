package com.bbororo.rtb.dsp.budget;

import com.bbororo.rtb.dsp.budget.BudgetMessages.CommitReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ExpireReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.InstallLease;
import com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseInstallResult;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReleaseReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationFinalization;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationResult;
import com.bbororo.rtb.dsp.budget.BudgetMessages.TryReserve;

/** 한 DSP 인스턴스의 리스 액면과 모든 예약 상태 변경을 단독 소유한다. */
public interface LocalBudgetAuthority extends CampaignPacingView {

    ReservationResult tryReserve(TryReserve command);

    ReservationFinalization release(ReleaseReservation command);

    ReservationFinalization commit(CommitReservation command);

    ReservationFinalization expire(ExpireReservation command);

    LeaseInstallResult install(InstallLease command);
}
