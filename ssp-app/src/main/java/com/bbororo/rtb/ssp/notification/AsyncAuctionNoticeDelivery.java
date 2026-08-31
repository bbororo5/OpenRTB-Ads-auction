package com.bbororo.rtb.ssp.notification;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionNotice;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** nurl·lurl을 경매 응답 경로 밖에서 단발 전달하는 장식자다. */
public final class AsyncAuctionNoticeDelivery implements DspNotificationDelivery, AutoCloseable {

    private final DspNotificationDelivery delegate;
    private final ExecutorService executor;

    public AsyncAuctionNoticeDelivery(DspNotificationDelivery delegate) {
        this(delegate, Executors.newVirtualThreadPerTaskExecutor());
    }

    AsyncAuctionNoticeDelivery(DspNotificationDelivery delegate, ExecutorService executor) {
        this.delegate = Objects.requireNonNull(delegate);
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public void sendAuctionNotices(List<AuctionNotice> notices) {
        List<AuctionNotice> immutableNotices = List.copyOf(notices);
        executor.submit(() -> delegate.sendAuctionNotices(immutableNotices));
    }

    @Override
    public BillingDeliveryAttempt deliverDueBilling(Instant now) {
        return delegate.deliverDueBilling(now);
    }

    @Override
    public void close() {
        executor.close();
    }
}
