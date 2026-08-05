package com.bbororo.rtb.dsp.lease;

import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefillResult;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult;
import com.bbororo.rtb.dsp.lease.LeaseMessages.RefillLease;
import com.bbororo.rtb.dsp.lease.LeaseMessages.SettlementWork;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** 보충과 정산의 제공 인터페이스를 두 협력 서비스에 위임한다. */
public final class DefaultLeaseLifecycle implements LeaseLifecycle {

    private final LeaseRefillService refillService;
    private final LeaseSettlementService settlementService;

    public DefaultLeaseLifecycle(
            LeaseRefillService refillService,
            LeaseSettlementService settlementService
    ) {
        this.refillService = Objects.requireNonNull(refillService, "refillService");
        this.settlementService = Objects.requireNonNull(settlementService, "settlementService");
    }

    @Override
    public CompletionStage<LeaseRefillResult> refill(RefillLease command) {
        return refillService.refill(command);
    }

    @Override
    public CompletionStage<LeaseSettlementResult> settle(SettlementWork work) {
        return settlementService.settle(work);
    }
}
