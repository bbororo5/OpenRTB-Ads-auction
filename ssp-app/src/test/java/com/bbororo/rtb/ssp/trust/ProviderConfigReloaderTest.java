package com.bbororo.rtb.ssp.trust;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProviderConfigReloaderTest {

    @Test
    void publishesOnlyANewerSnapshotLoadedFromTheRegionalReader() {
        ProviderTrustSnapshotHolder holder = new ProviderTrustSnapshotHolder(snapshot(1, true));
        AtomicInteger snapshotLoads = new AtomicInteger();
        ProviderConfigReader reader = new ProviderConfigReader() {
            @Override
            public long loadActiveVersion() {
                return 2;
            }

            @Override
            public ProviderTrustSnapshot loadActiveSnapshot() {
                snapshotLoads.incrementAndGet();
                return snapshot(2, false);
            }
        };
        ProviderConfigReloader reloader = new ProviderConfigReloader(reader, holder);

        assertTrue(reloader.refresh());
        assertFalse(holder.permits("provider-a", "key-a"));
        assertFalse(reloader.refresh());
        assertTrue(snapshotLoads.get() == 1);
    }

    private static ProviderTrustSnapshot snapshot(long version, boolean active) {
        return new ImmutableProviderTrustSnapshot(
                version,
                Map.of("provider-a", new ImmutableProviderTrustSnapshot.ProviderPolicy(active, Set.of("key-a")))
        );
    }
}
