package com.bbororo.rtb.dsp.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AuctionDeadlineTest {

    @Test
    void measuresRemainingTimeWithAMonotonicClock() {
        var nanos = new AtomicLong(1_000);
        var deadline = AuctionDeadline.start(50, nanos::get);

        assertEquals(Duration.ofMillis(50), deadline.remaining());
        nanos.addAndGet(Duration.ofMillis(49).toNanos());
        assertFalse(deadline.isExpired());
        nanos.addAndGet(Duration.ofMillis(1).toNanos());
        assertTrue(deadline.isExpired());
    }

    @Test
    void includesTimeAlreadySpentSinceHttpReceipt() {
        var nanos = new AtomicLong(Duration.ofMillis(12).toNanos());
        var deadline = AuctionDeadline.startAt(50, 0, nanos::get);

        assertEquals(Duration.ofMillis(38), deadline.remaining());
    }
}
