package com.bbororo.rtb.dsp.bidding.internal;

import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationRejection;
import java.util.Objects;

/** 캠페인 로컬 실패만 다음 후보로 넘기고 인스턴스·계약 실패는 요청을 멈춘다. */
public final class DefaultCandidateContinuationPolicy implements CandidateContinuationPolicy {

    @Override
    public Continuation after(ReservationRejection rejection) {
        Objects.requireNonNull(rejection, "rejection");
        return switch (rejection) {
            case CONTENDED,
                    NO_ACTIVE_LEASE,
                    INSUFFICIENT_LOCAL_BUDGET,
                    CAMPAIGN_CAPACITY_EXCEEDED,
                    LEASE_EXPIRED -> Continuation.TRY_NEXT_CANDIDATE;
            case INSTANCE_CAPACITY_EXCEEDED,
                    DUPLICATE_CONFLICT -> Continuation.STOP_REQUEST;
        };
    }
}
