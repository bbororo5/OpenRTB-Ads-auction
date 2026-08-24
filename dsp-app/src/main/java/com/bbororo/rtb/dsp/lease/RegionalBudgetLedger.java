package com.bbororo.rtb.dsp.lease;

import com.bbororo.rtb.dsp.lease.LeaseMessages.ClaimDueSettlements;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefillResult;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementAmounts;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult;
import com.bbororo.rtb.dsp.lease.LeaseMessages.RefillLease;
import com.bbororo.rtb.dsp.lease.LeaseMessages.SettlementWork;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** 리전 원장의 리스 발급과 멱등 정산을 호출하는 저장소 포트다. */
public interface RegionalBudgetLedger {

    CompletionStage<LeaseRefillResult> issue(RefillLease command);

    CompletionStage<List<SettlementWork>> claimDue(ClaimDueSettlements command);

    CompletionStage<LeaseSettlementResult> apply(
            SettlementWork work,
            LeaseSettlementAmounts settlement
    );
}
