package com.bbororo.rtb.dsp.outcome;

import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationExpiration;
import com.bbororo.rtb.dsp.budget.ReservationExpirationSource;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 만료 표식을 순서대로 가져와 저장 성공까지 같은 사건을 재시도한다. */
public final class ReservationExpirationWorker implements AutoCloseable {

    private final ReservationExpirationSource source;
    private final ReservationExpirationService service;
    private final Duration retryDelay;
    private final Consumer<Throwable> failureHandler;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread workerThread;

    public ReservationExpirationWorker(
            ReservationExpirationSource source,
            ReservationExpirationService service,
            Duration retryDelay,
            Consumer<Throwable> failureHandler
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.service = Objects.requireNonNull(service, "service");
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
        if (retryDelay.isNegative()) {
            throw new IllegalArgumentException("retryDelay must not be negative");
        }
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        workerThread = Thread.ofVirtual().name("reservation-expiration").start(this::runLoop);
    }

    private void runLoop() {
        while (running.get()) {
            try {
                ReservationExpiration expiration = source.takeDue();
                processUntilRecorded(expiration);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void processUntilRecorded(ReservationExpiration expiration) throws InterruptedException {
        while (running.get()) {
            try {
                service.expire(expiration).toCompletableFuture().join();
                return;
            } catch (CompletionException failure) {
                failureHandler.accept(failure.getCause() == null ? failure : failure.getCause());
                Thread.sleep(retryDelay);
            }
        }
    }

    @Override
    public void close() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }
}
