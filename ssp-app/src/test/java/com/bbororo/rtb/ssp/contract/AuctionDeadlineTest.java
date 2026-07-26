package com.bbororo.rtb.ssp.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AuctionDeadlineTest {

    @Test
    void measuresRemainingTimeWithTheMonotonicClock() {
        AtomicLong nanos = new AtomicLong(1_000L);
        AuctionDeadline deadline = AuctionDeadline.start(50, nanos::get);

        assertEquals(Duration.ofMillis(50), deadline.remaining());
        nanos.addAndGet(Duration.ofMillis(49).toNanos());
        assertFalse(deadline.isExpired());
        nanos.addAndGet(Duration.ofMillis(1).toNanos());
        assertTrue(deadline.isExpired());
    }

    @Test
    void rejectsATmaxOutsideTheProjectLimit() {
        assertThrows(IllegalArgumentException.class, () -> AuctionDeadline.start(0, System::nanoTime));
        assertThrows(IllegalArgumentException.class, () -> AuctionDeadline.start(181, System::nanoTime));
    }
}
