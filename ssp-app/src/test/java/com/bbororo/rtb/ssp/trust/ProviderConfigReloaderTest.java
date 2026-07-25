package com.bbororo.rtb.ssp.trust;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProviderConfigReloaderTest {

    @Test
    void publishesOnlyANewerSnapshotLoadedFromTheRegionalReader() {
        ProviderTrustSnapshotHolder holder = new ProviderTrustSnapshotHolder(snapshot(1, true));
        ProviderConfigReader reader = () -> snapshot(2, false);
        ProviderConfigReloader reloader = new ProviderConfigReloader(reader, holder);

        assertTrue(reloader.refresh());
        assertFalse(holder.permits("provider-a", "key-a"));
        assertFalse(reloader.refresh());
    }

    private static ProviderTrustSnapshot snapshot(long version, boolean active) {
        return new ImmutableProviderTrustSnapshot(
                version,
                Map.of("provider-a", new ImmutableProviderTrustSnapshot.ProviderPolicy(active, Set.of("key-a")))
        );
    }
}
