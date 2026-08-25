package com.bbororo.rtb.dsp.bidding.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.dsp.contract.AuctionDeadline;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DeadlineBidTimePolicyTest {

    @Test
    void startsWorkOnlyWhenCandidateCostAndPublicationReserveRemain() {
        var nanos = new AtomicLong();
        var deadline = AuctionDeadline.start(50, nanos::get);
        var policy = new DeadlineBidTimePolicy(
                Duration.ofMillis(10), Duration.ofMillis(5));

        assertTrue(policy.canStartSlot(deadline));
        assertTrue(policy.canStartCandidate(deadline));

        nanos.set(Duration.ofMillis(35).toNanos());

        assertFalse(policy.canStartSlot(deadline));
        assertFalse(policy.canStartCandidate(deadline));
        assertTrue(policy.canPublish(deadline));
    }

    @Test
    void exactPublicationReserveIsNotPublishable() {
        var nanos = new AtomicLong();
        var deadline = AuctionDeadline.start(50, nanos::get);
        var policy = new DeadlineBidTimePolicy(Duration.ZERO, Duration.ofMillis(5));

        nanos.set(Duration.ofMillis(45).toNanos());

        assertFalse(policy.canPublish(deadline));
    }
}
