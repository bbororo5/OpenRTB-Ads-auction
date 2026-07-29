package com.bbororo.rtb.ssp.notification;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 인스턴스 안에서 지역 DB의 burl 전달 작업을 짧은 주기로 한 건씩 처리한다. */
public final class BillingDeliveryWorker implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(BillingDeliveryWorker.class.getName());

    private final DspNotificationDelivery delivery;
    private final Clock clock;
    private final Duration interval;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean();
    private ScheduledFuture<?> task;

    public BillingDeliveryWorker(DspNotificationDelivery delivery, Clock clock, Duration interval) {
        this(delivery, clock, interval, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ssp-billing-delivery");
            thread.setDaemon(true);
            return thread;
        }));
    }

    BillingDeliveryWorker(
            DspNotificationDelivery delivery,
            Clock clock,
            Duration interval,
            ScheduledExecutorService executor
    ) {
        this.delivery = Objects.requireNonNull(delivery);
        this.clock = Objects.requireNonNull(clock);
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        this.interval = interval;
        this.executor = Objects.requireNonNull(executor);
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("billing delivery worker is already started");
        }
        task = executor.scheduleWithFixedDelay(
                this::runSafely,
                0,
                interval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    /** 한 번의 실행은 한 작업만 처리해 다른 호출의 재시도 시간을 독점하지 않는다. */
    public void runOnce() {
        delivery.deliverDueBilling(clock.instant());
    }

    private void runSafely() {
        try {
            runOnce();
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.ERROR, "burl 전달 작업 실행에 실패했습니다.", exception);
        }
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel(false);
        }
        executor.shutdown();
    }
}
