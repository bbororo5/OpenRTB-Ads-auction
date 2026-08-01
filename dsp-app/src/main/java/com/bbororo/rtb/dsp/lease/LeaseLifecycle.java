package com.bbororo.rtb.dsp.lease;

import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefillResult;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult;
import com.bbororo.rtb.dsp.lease.LeaseMessages.RefillLease;
import com.bbororo.rtb.dsp.lease.LeaseMessages.SettleLease;
import java.util.concurrent.CompletionStage;

/** 입찰 경로 밖에서 로컬 예산 권한을 보충하고 끝난 리스를 정산한다. */
public interface LeaseLifecycle {

    CompletionStage<LeaseRefillResult> refill(RefillLease command);

    CompletionStage<LeaseSettlementResult> settle(SettleLease command);
}
