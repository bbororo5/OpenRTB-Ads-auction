package com.bbororo.rtb.ssp.contract;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** 단조 시계로 계산한 경매 실행의 절대 마감이다. */
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
        Objects.requireNonNull(monotonicNanos);
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
