package com.bbororo.rtb.ssp.notification;

import com.bbororo.rtb.ssp.claim.ClaimDeliveryStore;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionNotice;
import com.bbororo.rtb.ssp.contract.SspMessages.DeliveryOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 경매 통지는 단발로 보내고, 과금 통지는 저장된 작업을 임대해 전달 결과를 기록한다.
 */
public final class StoreBackedDspNotificationDelivery implements DspNotificationDelivery {

    private final ClaimDeliveryStore store;
    private final DspNoticeClient client;

    public StoreBackedDspNotificationDelivery(ClaimDeliveryStore store, DspNoticeClient client) {
        this.store = Objects.requireNonNull(store);
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public void sendAuctionNotices(List<AuctionNotice> notices) {
        Objects.requireNonNull(notices);
        notices.forEach(notice -> sendSafely(notice.url()));
    }

    @Override
    public void deliverDueBilling(Instant now) {
        Objects.requireNonNull(now);
        store.leaseDueDelivery(now).ifPresent(delivery -> {
            DeliveryOutcome outcome = sendSafely(delivery.task().claim().billingUrl());
            store.completeOrReleaseDelivery(delivery.lease(), outcome, now);
        });
    }

    private DeliveryOutcome sendSafely(java.net.URI url) {
        try {
            return Objects.requireNonNull(client.send(url), "DspNoticeClient must return an outcome");
        } catch (RuntimeException exception) {
            return DeliveryOutcome.RETRY;
        }
    }
}
