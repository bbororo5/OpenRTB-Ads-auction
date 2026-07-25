package com.bbororo.rtb.ssp.trust;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ProviderTrustControlPlaneTest {

    @Test
    void exposesTheInitialSnapshotOnlyAfterItHasLoadedSuccessfully() {
        TrackingResource database = new TrackingResource();
        ProviderConfigReader reader = reader(snapshot(1));

        try (ProviderTrustControlPlane controlPlane = ProviderTrustControlPlane.start(
                reader,
                database,
                Executors.newSingleThreadScheduledExecutor(),
                Duration.ofSeconds(10),
                Duration.ofDays(1)
        )) {
            assertTrue(controlPlane.trustSnapshot().permits("provider-a", "key-a"));
        }

        assertTrue(database.closed.get());
    }

    @Test
    void closesTheDatabaseResourceWhenInitialLoadingFails() {
        TrackingResource database = new TrackingResource();
        ProviderConfigReader failingReader = new ProviderConfigReader() {
            @Override
            public long loadActiveVersion() {
                throw new AssertionError("최초 적재 전에 호출하면 안 됩니다.");
            }

            @Override
            public ProviderTrustSnapshot loadActiveSnapshot() {
                throw new IllegalStateException("지역 DB 연결 실패");
            }
        };

        assertThrows(IllegalStateException.class, () -> ProviderTrustControlPlane.start(
                failingReader,
                database,
                Executors.newSingleThreadScheduledExecutor(),
                Duration.ofSeconds(10),
                Duration.ZERO
        ));
        assertTrue(database.closed.get());
    }

    private static ProviderConfigReader reader(ProviderTrustSnapshot snapshot) {
        return new ProviderConfigReader() {
            @Override
            public long loadActiveVersion() {
                return snapshot.version();
            }

            @Override
            public ProviderTrustSnapshot loadActiveSnapshot() {
                return snapshot;
            }
        };
    }

    private static ProviderTrustSnapshot snapshot(long version) {
        return new ImmutableProviderTrustSnapshot(
                version,
                Map.of("provider-a", new ImmutableProviderTrustSnapshot.ProviderPolicy(true, Set.of("key-a")))
        );
    }

    private static final class TrackingResource implements AutoCloseable {

        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
