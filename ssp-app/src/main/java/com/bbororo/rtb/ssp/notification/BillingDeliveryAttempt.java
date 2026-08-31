package com.bbororo.rtb.ssp.notification;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** 과금 전달 단건 실행 여부와 내구 저장소가 결정한 다음 실행 시각이다. */
public record BillingDeliveryAttempt(
        boolean processed,
        Optional<Instant> retryAt
) {

    public BillingDeliveryAttempt {
        retryAt = Objects.requireNonNull(retryAt, "retryAt");
        if (!processed && retryAt.isPresent()) {
            throw new IllegalArgumentException("an empty attempt cannot schedule a retry");
        }
    }

    public static BillingDeliveryAttempt empty() {
        return new BillingDeliveryAttempt(false, Optional.empty());
    }

    public static BillingDeliveryAttempt completed() {
        return new BillingDeliveryAttempt(true, Optional.empty());
    }

    public static BillingDeliveryAttempt retryScheduled(Instant retryAt) {
        return new BillingDeliveryAttempt(true, Optional.of(
                Objects.requireNonNull(retryAt, "retryAt")));
    }
}
