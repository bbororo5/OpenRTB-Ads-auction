package com.bbororo.rtb.dsp.bidding.internal;

import static com.bbororo.rtb.dsp.bidding.internal.CandidateContinuationPolicy.Continuation.STOP_REQUEST;
import static com.bbororo.rtb.dsp.bidding.internal.CandidateContinuationPolicy.Continuation.TRY_NEXT_CANDIDATE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationRejection;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class DefaultCandidateContinuationPolicyTest {

    private final DefaultCandidateContinuationPolicy policy =
            new DefaultCandidateContinuationPolicy();

    @Test
    void retriesFailuresConfinedToOneCampaignCandidate() {
        var retryable = EnumSet.of(
                ReservationRejection.CONTENDED,
                ReservationRejection.NO_ACTIVE_LEASE,
                ReservationRejection.INSUFFICIENT_LOCAL_BUDGET,
                ReservationRejection.CAMPAIGN_CAPACITY_EXCEEDED,
                ReservationRejection.LEASE_EXPIRED
        );

        retryable.forEach(reason -> assertEquals(
                TRY_NEXT_CANDIDATE, policy.after(reason), reason.name()));
    }

    @Test
    void haltsTheRequestForInstanceOrContractFailures() {
        assertEquals(STOP_REQUEST,
                policy.after(ReservationRejection.INSTANCE_CAPACITY_EXCEEDED));
        assertEquals(STOP_REQUEST,
                policy.after(ReservationRejection.DUPLICATE_CONFLICT));
    }
}
