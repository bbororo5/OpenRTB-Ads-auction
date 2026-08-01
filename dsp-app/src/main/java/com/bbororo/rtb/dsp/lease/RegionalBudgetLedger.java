package com.bbororo.rtb.dsp.lease;

import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefillResult;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlement;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult;
import com.bbororo.rtb.dsp.lease.LeaseMessages.RefillLease;
import java.util.concurrent.CompletionStage;

/** 리전 원장의 리스 발급과 멱등 정산을 호출하는 저장소 포트다. */
public interface RegionalBudgetLedger {

    CompletionStage<LeaseRefillResult> issue(RefillLease command);

    CompletionStage<LeaseSettlementResult> apply(LeaseSettlement settlement);
}
