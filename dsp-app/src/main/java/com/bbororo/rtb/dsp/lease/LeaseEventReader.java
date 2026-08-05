package com.bbororo.rtb.dsp.lease;

import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseUsageSummary;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

/** 지역 금액 사건에서 리스별 소비·반환·격리 근거를 재생하는 저장소 포트다. */
public interface LeaseEventReader {

    CompletionStage<LeaseUsageSummary> summarize(
            String leaseId,
            long faceValueMicros,
            Instant evaluatedAt
    );
}
