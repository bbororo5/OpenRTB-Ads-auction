package com.bbororo.rtb.ssp.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 내구 과금 작업 신호와 재시도 시각에만 깨어나는 제한 동시성 전달기다. */
public final class BillingDeliveryWorker implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(BillingDeliveryWorker.class.getName());
    private static final Duration FAILURE_RETRY_DELAY = Duration.ofMillis(100);

    private final DspNotificationDelivery delivery;
    private final Clock clock;
    private final int concurrency;
    private final ExecutorService workers;
    private final ScheduledExecutorService timer;
    private final Semaphore workSignals = new Semaphore(0);
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public BillingDeliveryWorker(
            DspNotificationDelivery delivery,
            Clock clock,
            int concurrency
    ) {
        this(
                delivery,
                clock,
                concurrency,
                newWorkerExecutor(concurrency),
                newTimerExecutor()
        );
    }

    BillingDeliveryWorker(
            DspNotificationDelivery delivery,
            Clock clock,
            int concurrency,
            ExecutorService workers,
            ScheduledExecutorService timer
    ) {
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (concurrency <= 0) {
            throw new IllegalArgumentException("concurrency must be positive");
        }
        this.concurrency = concurrency;
        this.workers = Objects.requireNonNull(workers, "workers");
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    public void start() {
        if (closed.get() || !started.compareAndSet(false, true)) {
            throw new IllegalStateException("billing delivery worker cannot be started");
        }
        for (int index = 0; index < concurrency; index++) {
            workers.execute(this::runLoop);
        }
    }

    /** DB commit 뒤 새 과금 작업 하나가 생겼음을 알린다. */
    public void signal() {
        if (!closed.get()) {
            workSignals.release();
        }
    }

    /** 복구한 작업이나 내구 저장소가 정한 재시도를 정확한 시각에 깨운다. */
    public void schedule(Instant dueAt) {
        Objects.requireNonNull(dueAt, "dueAt");
        if (closed.get()) {
            return;
        }
        long delayNanos = Math.max(
                0L,
                Duration.between(clock.instant(), dueAt).toNanos()
        );
        timer.schedule(this::signal, delayNanos, TimeUnit.NANOSECONDS);
    }

    /** 동기 조립 시험용 단건 실행이다. */
    public BillingDeliveryAttempt runOnce() {
        return delivery.deliverDueBilling(clock.instant());
    }

    private void runLoop() {
        while (!closed.get()) {
            try {
                workSignals.acquire();
                if (closed.get()) {
                    return;
                }
                BillingDeliveryAttempt attempt = runOnce();
                attempt.retryAt().ifPresent(this::schedule);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                LOGGER.log(System.Logger.Level.ERROR, "burl 전달 작업 실행에 실패했습니다.", failure);
                schedule(clock.instant().plus(FAILURE_RETRY_DELAY));
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        timer.shutdownNow();
        workers.shutdownNow();
    }

    private static ExecutorService newWorkerExecutor(int concurrency) {
        if (concurrency <= 0) {
            throw new IllegalArgumentException("concurrency must be positive");
        }
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "ssp-billing-delivery-" + sequence.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        });
    }

    private static ScheduledExecutorService newTimerExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ssp-billing-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }
}
