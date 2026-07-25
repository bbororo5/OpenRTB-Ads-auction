package com.bbororo.rtb.ssp.trust;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProviderTrustSnapshotHolderTest {

    @Test
    void exposesOnlyWholeSnapshotsAndRejectsStaleVersions() {
        ProviderTrustSnapshotHolder holder = new ProviderTrustSnapshotHolder(snapshot(1, true));

        assertTrue(holder.permits("provider-a", "key-a"));
        assertTrue(holder.replaceIfNewer(snapshot(2, false)));
        assertFalse(holder.permits("provider-a", "key-a"));

        assertFalse(holder.replaceIfNewer(snapshot(1, true)));
        assertFalse(holder.permits("provider-a", "key-a"));
    }

    private static ProviderTrustSnapshot snapshot(long version, boolean active) {
        return new ImmutableProviderTrustSnapshot(
                version,
                Map.of("provider-a", new ImmutableProviderTrustSnapshot.ProviderPolicy(active, Set.of("key-a")))
        );
    }
}
