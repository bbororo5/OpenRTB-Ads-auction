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
        Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        return startAt(tmaxMillis, monotonicNanos.getAsLong(), monotonicNanos);
    }

    /** HTTP 요청을 처음 받은 시점부터 tmax를 차감한다. */
    public static AuctionDeadline startAt(
            int tmaxMillis,
            long receivedNanos,
            LongSupplier monotonicNanos
    ) {
        if (tmaxMillis <= 0 || tmaxMillis > 180) {
            throw new IllegalArgumentException("tmaxMillis must be between 1 and 180");
        }
        Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        return new AuctionDeadline(
                Math.addExact(receivedNanos, Duration.ofMillis(tmaxMillis).toNanos()),
                monotonicNanos
        );
    }

    public boolean isExpired() {
        return remaining().isZero();
    }

    public Duration remaining() {
        long remainingNanos = deadlineNanos - monotonicNanos.getAsLong();
        return Duration.ofNanos(Math.max(0, remainingNanos));
    }
}
