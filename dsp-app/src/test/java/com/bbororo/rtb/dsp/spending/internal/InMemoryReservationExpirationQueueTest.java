package com.bbororo.rtb.dsp.spending.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationExpiration;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationReference;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class InMemoryReservationExpirationQueueTest {

    @Test
    void exposesOnlyDueExpirationCandidates() throws InterruptedException {
        Instant now = Instant.parse("2026-01-01T00:00:05Z");
        var nanos = new AtomicLong(10_000L);
        var queue = new InMemoryReservationExpirationQueue(
                Clock.fixed(now, ZoneOffset.UTC),
                nanos::get
        );
        var expiration = new ReservationExpiration(
                new ReservationReference("campaign-1", "lease-1", "reservation-1"),
                1_000,
                now
        );

        queue.schedule(expiration);

        assertEquals(1, queue.scheduledCount());
        assertEquals(expiration, queue.takeDue());
        assertEquals(0, queue.scheduledCount());
    }
}
