package com.bbororo.rtb.dsp.lease;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 리스 유지관리 주기를 실행하되 앞선 주기가 끝나지 않았으면 작업자가 겹침을 거부하게 한다. */
public final class LeaseMaintenanceScheduler implements AutoCloseable {

    private final LeaseMaintenanceWorker worker;
    private final Duration interval;
    private final Consumer<Throwable> failureHandler;
    private final ScheduledExecutorService scheduler;

    public LeaseMaintenanceScheduler(
            LeaseMaintenanceWorker worker,
            Duration interval,
            Consumer<Throwable> failureHandler
    ) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        if (interval.toMillis() == 0L) {
            throw new IllegalArgumentException("interval must be at least one millisecond");
        }
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().daemon().name("lease-maintenance").unstarted(runnable)
        );
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(
                () -> worker.runOnce().whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        failureHandler.accept(failure);
                    }
                }),
                0L,
                interval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
