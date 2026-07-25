package com.bbororo.rtb.ssp.trust;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 지역 설정 버전을 주기적으로 확인하는 제어 경로 실행기다. */
public final class ProviderConfigRefreshScheduler implements AutoCloseable {

    public static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(10);

    private static final System.Logger LOGGER = System.getLogger(ProviderConfigRefreshScheduler.class.getName());

    private final ProviderConfigReloader reloader;
    private final ScheduledExecutorService executor;
    private final Duration refreshInterval;
    private final AtomicBoolean started = new AtomicBoolean();
    private ScheduledFuture<?> scheduledTask;

    public ProviderConfigRefreshScheduler(ProviderConfigReloader reloader) {
        this(
                reloader,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "provider-config-refresh");
                    thread.setDaemon(true);
                    return thread;
                }),
                DEFAULT_REFRESH_INTERVAL
        );
    }

    ProviderConfigRefreshScheduler(
            ProviderConfigReloader reloader,
            ScheduledExecutorService executor,
            Duration refreshInterval
    ) {
        this.reloader = Objects.requireNonNull(reloader);
        this.executor = Objects.requireNonNull(executor);
        this.refreshInterval = requirePositive(refreshInterval, "refreshInterval");
    }

    /** 인스턴스 간 동시 조회를 줄이기 위해 첫 실행 시점만 무작위로 분산한다. */
    public void start() {
        start(Duration.ofMillis(ThreadLocalRandom.current().nextLong(refreshInterval.toMillis())));
    }

    void start(Duration initialDelay) {
        Duration validatedInitialDelay = requireNonNegative(initialDelay, "initialDelay");
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("공급자 설정 갱신기는 이미 시작했습니다.");
        }
        scheduledTask = executor.scheduleWithFixedDelay(
                this::refreshSafely,
                validatedInitialDelay.toMillis(),
                refreshInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private void refreshSafely() {
        try {
            reloader.refresh();
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.ERROR, "공급자 설정 갱신에 실패했습니다. 기존 스냅숏을 유지합니다.", exception);
        }
    }

    @Override
    public void close() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        executor.shutdown();
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("%s은 양수여야 합니다.".formatted(name));
        }
        return value;
    }

    private static Duration requireNonNegative(Duration value, String name) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException("%s은 0 이상이어야 합니다.".formatted(name));
        }
        return value;
    }
}
