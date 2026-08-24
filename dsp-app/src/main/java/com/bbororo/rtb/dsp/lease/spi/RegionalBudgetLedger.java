package com.bbororo.rtb.dsp.lease.spi;

import com.bbororo.rtb.dsp.lease.api.LeaseMessages.ClaimDueSettlements;
import com.bbororo.rtb.dsp.lease.api.LeaseMessages.LeaseRefillResult;
import com.bbororo.rtb.dsp.lease.api.LeaseMessages.LeaseSettlementAmounts;
import com.bbororo.rtb.dsp.lease.api.LeaseMessages.LeaseSettlementResult;
import com.bbororo.rtb.dsp.lease.api.LeaseMessages.RefillLease;
import com.bbororo.rtb.dsp.lease.api.LeaseMessages.SettlementWork;
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
