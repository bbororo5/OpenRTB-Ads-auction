package com.bbororo.rtb.dsp.lease;

import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult;
import com.bbororo.rtb.dsp.lease.LeaseMessages.SettlementWork;
import java.util.concurrent.CompletionStage;

/** 안전 회복 시점이 지난 리스의 금액을 분류해 원장에 멱등 정산한다. */
public interface LeaseSettlement {

    CompletionStage<LeaseSettlementResult> settle(SettlementWork work);
}
