package com.bbororo.rtb.ssp;

import com.bbororo.rtb.ssp.api.ProviderHttpServer;
import com.bbororo.rtb.ssp.notification.AsyncAuctionNoticeDelivery;
import com.bbororo.rtb.ssp.notification.BillingDeliveryWorker;
import com.bbororo.rtb.ssp.trust.ProviderTrustControlPlane;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

/** 조립된 SSP의 시작·대기·종료 순서를 소유한다. */
public final class SspRuntime implements AutoCloseable {

    private final ProviderHttpServer server;
    private final BillingDeliveryWorker billingWorker;
    private final AsyncAuctionNoticeDelivery notificationDelivery;
    private final List<HttpClient> bidClients;
    private final HttpClient noticeClient;
    private final ExecutorService auctionExecutor;
    private final ProviderTrustControlPlane trustControl;
    private final CountDownLatch stopped = new CountDownLatch(1);

    private boolean started;
    private boolean closed;

    SspRuntime(
            ProviderHttpServer server,
            BillingDeliveryWorker billingWorker,
            AsyncAuctionNoticeDelivery notificationDelivery,
            List<HttpClient> bidClients,
            HttpClient noticeClient,
            ExecutorService auctionExecutor,
            ProviderTrustControlPlane trustControl
    ) {
        this.server = Objects.requireNonNull(server);
        this.billingWorker = Objects.requireNonNull(billingWorker);
        this.notificationDelivery = Objects.requireNonNull(notificationDelivery);
        this.bidClients = List.copyOf(bidClients);
        this.noticeClient = Objects.requireNonNull(noticeClient);
        this.auctionExecutor = Objects.requireNonNull(auctionExecutor);
        this.trustControl = Objects.requireNonNull(trustControl);
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("SSP runtime is already closed");
        }
        if (started) {
            throw new IllegalStateException("SSP runtime is already started");
        }
        try {
            billingWorker.start();
            server.start();
            started = true;
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    public void awaitShutdown() throws InterruptedException {
        stopped.await();
    }

    public synchronized int activePort() {
        if (!started || closed) {
            throw new IllegalStateException("SSP runtime is not running");
        }
        return server.port();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        failure = close(server, failure);
        failure = close(billingWorker, failure);
        failure = close(auctionExecutor, failure);
        failure = close(notificationDelivery, failure);
        for (HttpClient bidClient : bidClients) {
            failure = close(bidClient, failure);
        }
        failure = close(noticeClient, failure);
        failure = close(trustControl, failure);
        stopped.countDown();
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException close(
            AutoCloseable resource,
            RuntimeException previousFailure
    ) {
        try {
            resource.close();
            return previousFailure;
        } catch (Exception exception) {
            RuntimeException failure = exception instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new IllegalStateException("Could not close SSP runtime resource", exception);
            if (previousFailure == null) {
                return failure;
            }
            previousFailure.addSuppressed(failure);
            return previousFailure;
        }
    }
}
