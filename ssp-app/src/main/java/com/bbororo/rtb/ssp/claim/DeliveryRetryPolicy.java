package com.bbororo.rtb.ssp.claim;

import java.time.Duration;

/** 전달 시도 세대에 따라 제한된 지수 재시도 간격을 계산한다. */
public record DeliveryRetryPolicy(Duration initialDelay, Duration maxDelay) {

    public DeliveryRetryPolicy {
        if (initialDelay == null || initialDelay.isZero() || initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must be positive");
        }
        if (maxDelay == null || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must not be shorter than initialDelay");
        }
    }

    public static DeliveryRetryPolicy standard() {
        return new DeliveryRetryPolicy(
                Duration.ofMillis(50),
                Duration.ofMillis(500)
        );
    }

    public Duration delayAfter(long deliveryGeneration) {
        if (deliveryGeneration <= 0) {
            throw new IllegalArgumentException("deliveryGeneration must be positive");
        }
        Duration delay = initialDelay;
        for (long generation = 1;
             generation < deliveryGeneration && delay.compareTo(maxDelay) < 0;
             generation++) {
            delay = delay.compareTo(maxDelay.dividedBy(2)) >= 0
                    ? maxDelay
                    : delay.multipliedBy(2);
        }
        return delay;
    }
}
