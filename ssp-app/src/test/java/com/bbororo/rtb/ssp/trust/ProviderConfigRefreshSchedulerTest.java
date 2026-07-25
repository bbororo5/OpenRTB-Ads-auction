package com.bbororo.rtb.ssp.trust;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ProviderConfigRefreshSchedulerTest {

    @Test
    void checksTheRegionalVersionAgainAfterEachFixedDelay() throws InterruptedException {
        CountDownLatch versionChecks = new CountDownLatch(2);
        ProviderConfigReader reader = new ProviderConfigReader() {
            @Override
            public long loadActiveVersion() {
                versionChecks.countDown();
                return 1;
            }

            @Override
            public ProviderTrustSnapshot loadActiveSnapshot() {
                throw new AssertionError("같은 버전에서는 전체 스냅숏을 읽으면 안 됩니다.");
            }
        };
        ProviderTrustSnapshotHolder holder = new ProviderTrustSnapshotHolder(snapshot(1));
        ProviderConfigReloader reloader = new ProviderConfigReloader(reader, holder);

        try (ProviderConfigRefreshScheduler scheduler = new ProviderConfigRefreshScheduler(
                reloader,
                Executors.newSingleThreadScheduledExecutor(),
                Duration.ofMillis(10)
        )) {
            scheduler.start(Duration.ZERO);
            assertTrue(versionChecks.await(1, TimeUnit.SECONDS));
        }
    }

    private static ProviderTrustSnapshot snapshot(long version) {
        return new ImmutableProviderTrustSnapshot(
                version,
                Map.of("provider-a", new ImmutableProviderTrustSnapshot.ProviderPolicy(true, Set.of("key-a")))
        );
    }
}
