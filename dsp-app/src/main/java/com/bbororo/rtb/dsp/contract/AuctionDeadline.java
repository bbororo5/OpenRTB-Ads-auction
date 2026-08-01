package com.bbororo.rtb.dsp.contract;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** DSP 입찰의 남은 시간을 운영 시각 보정에 영향받지 않는 단조 시계로 측정한다. */
public final class AuctionDeadline {

    private final long deadlineNanos;
    private final LongSupplier monotonicNanos;

    private AuctionDeadline(long deadlineNanos, LongSupplier monotonicNanos) {
        this.deadlineNanos = deadlineNanos;
        this.monotonicNanos = monotonicNanos;
    }

    public static AuctionDeadline start(int tmaxMillis, LongSupplier monotonicNanos) {
        if (tmaxMillis <= 0 || tmaxMillis > 180) {
            throw new IllegalArgumentException("tmaxMillis must be between 1 and 180");
        }
        Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        long now = monotonicNanos.getAsLong();
        return new AuctionDeadline(Math.addExact(now, Duration.ofMillis(tmaxMillis).toNanos()), monotonicNanos);
    }

    public boolean isExpired() {
        return remaining().isZero();
    }

    public Duration remaining() {
        long remainingNanos = deadlineNanos - monotonicNanos.getAsLong();
        return Duration.ofNanos(Math.max(0, remainingNanos));
    }
}
