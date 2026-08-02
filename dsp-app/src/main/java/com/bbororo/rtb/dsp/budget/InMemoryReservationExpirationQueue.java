package com.bbororo.rtb.dsp.budget;

import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationExpiration;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** 단조 시계로 만료 순서를 관리하며 금액 상태는 직접 바꾸지 않는다. */
public final class InMemoryReservationExpirationQueue
        implements ReservationExpirationSink, ReservationExpirationSource {

    private final Clock wallClock;
    private final LongSupplier monotonicNanos;
    private final DelayQueue<DelayedExpiration> queue = new DelayQueue<>();

    public InMemoryReservationExpirationQueue() {
        this(Clock.systemUTC(), System::nanoTime);
    }

    InMemoryReservationExpirationQueue(Clock wallClock, LongSupplier monotonicNanos) {
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    }

    @Override
    public void schedule(ReservationExpiration expiration) {
        Objects.requireNonNull(expiration, "expiration");
        long remainingNanos = Math.max(0L,
                Duration.between(wallClock.instant(), expiration.expiresAt()).toNanos());
        long deadlineNanos = Math.addExact(monotonicNanos.getAsLong(), remainingNanos);
        queue.add(new DelayedExpiration(expiration, deadlineNanos, monotonicNanos));
    }

    @Override
    public ReservationExpiration takeDue() throws InterruptedException {
        return queue.take().expiration();
    }

    int scheduledCount() {
        return queue.size();
    }

    private record DelayedExpiration(
            ReservationExpiration expiration,
            long deadlineNanos,
            LongSupplier monotonicNanos
    ) implements Delayed {

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(deadlineNanos - monotonicNanos.getAsLong(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other == this) {
                return 0;
            }
            return Long.compare(deadlineNanos, ((DelayedExpiration) other).deadlineNanos);
        }
    }
}
