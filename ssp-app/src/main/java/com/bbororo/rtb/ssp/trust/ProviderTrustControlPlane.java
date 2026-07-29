package com.bbororo.rtb.ssp.trust;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 공급자 신뢰 설정의 시작·갱신 수명주기를 조립한다.
 *
 * <p>후속 {@code SspApplication}은 HTTP 포트를 열기 전에 이 조립체를 만들고, 노출한 신뢰 스냅숏만
 * 경매 API에 전달한다. 최초 적재가 실패하면 이 메서드는 반환하지 않으므로, 신뢰 설정 없는 SSP가
 * 요청을 수락하는 상태는 만들지 않는다.</p>
 */
public final class ProviderTrustControlPlane implements AutoCloseable {

    private final ProviderTrustSnapshotHolder snapshots;
    private final ProviderConfigRefreshScheduler refreshScheduler;
    private final AutoCloseable databaseResource;

    private ProviderTrustControlPlane(
            ProviderTrustSnapshotHolder snapshots,
            ProviderConfigRefreshScheduler refreshScheduler,
            AutoCloseable databaseResource
    ) {
        this.snapshots = snapshots;
        this.refreshScheduler = refreshScheduler;
        this.databaseResource = databaseResource;
    }

    /** 배포 환경이 제공한 현재 지역 DB에 연결해 제어 경로를 시작한다. */
    public static ProviderTrustControlPlane startFromEnvironment() {
        HikariDataSource dataSource = RegionalDataSourceFactory.createFromEnvironment();
        return start(dataSource);
    }

    /** 애플리케이션이 청구 저장소와 공유하는 지역 연결 풀로 제어 경로를 시작한다. */
    public static ProviderTrustControlPlane start(HikariDataSource dataSource) {
        return start(
                new PostgreSqlProviderConfigReader(dataSource),
                dataSource,
                ProviderConfigRefreshScheduler.newExecutor(),
                ProviderConfigRefreshScheduler.DEFAULT_REFRESH_INTERVAL,
                randomInitialDelay()
        );
    }

    static ProviderTrustControlPlane start(
            ProviderConfigReader reader,
            AutoCloseable databaseResource,
            ScheduledExecutorService executor,
            Duration refreshInterval,
            Duration initialDelay
    ) {
        Objects.requireNonNull(reader);
        Objects.requireNonNull(databaseResource);
        Objects.requireNonNull(executor);

        try {
            ProviderTrustSnapshotHolder snapshots = new ProviderTrustSnapshotHolder(reader.loadActiveSnapshot());
            ProviderConfigReloader reloader = new ProviderConfigReloader(reader, snapshots);
            ProviderConfigRefreshScheduler scheduler = new ProviderConfigRefreshScheduler(
                    reloader,
                    executor,
                    refreshInterval
            );
            scheduler.start(initialDelay);
            return new ProviderTrustControlPlane(snapshots, scheduler, databaseResource);
        } catch (RuntimeException exception) {
            executor.shutdownNow();
            closeDatabaseResource(databaseResource);
            throw exception;
        }
    }

    /** 경매 API가 읽을 현재 지역 신뢰 스냅숏이다. */
    public ProviderTrustSnapshot trustSnapshot() {
        return snapshots;
    }

    @Override
    public void close() {
        refreshScheduler.close();
        closeDatabaseResource(databaseResource);
    }

    private static Duration randomInitialDelay() {
        long intervalMillis = ProviderConfigRefreshScheduler.DEFAULT_REFRESH_INTERVAL.toMillis();
        return Duration.ofMillis(java.util.concurrent.ThreadLocalRandom.current().nextLong(intervalMillis));
    }

    private static void closeDatabaseResource(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception exception) {
            throw new IllegalStateException("공급자 설정 DB 자원을 닫지 못했습니다.", exception);
        }
    }
}
