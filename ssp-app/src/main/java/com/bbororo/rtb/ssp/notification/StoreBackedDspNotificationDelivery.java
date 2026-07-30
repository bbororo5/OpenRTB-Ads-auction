package com.bbororo.rtb.ssp.notification;

import com.bbororo.rtb.ssp.claim.ClaimDeliveryStore;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionNotice;
import com.bbororo.rtb.ssp.contract.SspMessages.DeliveryOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 경매 통지는 단발로 보내고, 과금 통지는 저장된 작업을 임대해 전달 결과를 기록한다.
 */
public final class StoreBackedDspNotificationDelivery implements DspNotificationDelivery {

    private final ClaimDeliveryStore store;
    private final DspNoticeClient client;
    private final Clock clock;
    private final Duration maxAttemptDuration;

    public StoreBackedDspNotificationDelivery(
            ClaimDeliveryStore store,
            DspNoticeClient client,
            Clock clock,
            Duration maxAttemptDuration
    ) {
        this.store = Objects.requireNonNull(store);
        this.client = Objects.requireNonNull(client);
        this.clock = Objects.requireNonNull(clock);
        if (maxAttemptDuration == null
                || maxAttemptDuration.isZero()
                || maxAttemptDuration.isNegative()) {
            throw new IllegalArgumentException("maxAttemptDuration must be positive");
        }
        this.maxAttemptDuration = maxAttemptDuration;
    }

    @Override
    public void sendAuctionNotices(List<AuctionNotice> notices) {
        Objects.requireNonNull(notices);
        notices.forEach(notice -> sendSafely(notice.url(), maxAttemptDuration));
    }

    @Override
    public void deliverDueBilling(Instant now) {
        Objects.requireNonNull(now);
        store.leaseDueDelivery(now).ifPresent(delivery -> {
            Instant attemptStartedAt = notBefore(clock.instant(), now);
            Duration remaining = Duration.between(
                    attemptStartedAt,
                    delivery.task().claim().billingDeadline()
            );
            DeliveryOutcome outcome = remaining.isZero() || remaining.isNegative()
                    ? DeliveryOutcome.UNDELIVERED
                    : sendSafely(
                            delivery.task().claim().billingUrl(),
                            shorterOf(maxAttemptDuration, remaining)
                    );
            Instant completedAt = notBefore(clock.instant(), attemptStartedAt);
            store.completeOrReleaseDelivery(delivery.lease(), outcome, completedAt);
        });
    }

    private DeliveryOutcome sendSafely(java.net.URI url, Duration timeout) {
        try {
            return Objects.requireNonNull(
                    client.send(url, timeout),
                    "DspNoticeClient must return an outcome"
            );
        } catch (RuntimeException exception) {
            return DeliveryOutcome.RETRY;
        }
    }

    private static Duration shorterOf(Duration first, Duration second) {
        return first.compareTo(second) < 0 ? first : second;
    }

    private static Instant notBefore(Instant candidate, Instant minimum) {
        return candidate.isBefore(minimum) ? minimum : candidate;
    }
}
