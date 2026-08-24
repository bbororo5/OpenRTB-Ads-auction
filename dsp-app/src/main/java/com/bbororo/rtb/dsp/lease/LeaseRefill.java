package com.bbororo.rtb.dsp.lease;

import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefillResult;
import com.bbororo.rtb.dsp.lease.LeaseMessages.RefillLease;
import java.util.concurrent.CompletionStage;

/** Regional Budget Ledger에서 새 위임 권한을 받아 로컬에 설치한다. */
public interface LeaseRefill {

    CompletionStage<LeaseRefillResult> refill(RefillLease command);
}
