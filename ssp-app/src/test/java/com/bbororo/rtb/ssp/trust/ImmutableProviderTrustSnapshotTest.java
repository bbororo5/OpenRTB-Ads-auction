package com.bbororo.rtb.ssp.trust;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImmutableProviderTrustSnapshotTest {

    @Test
    void permitsOnlyAnActiveProviderAndActiveKey() {
        ProviderTrustSnapshot snapshot = new ImmutableProviderTrustSnapshot(
                7,
                Map.of(
                        "provider-active", new ImmutableProviderTrustSnapshot.ProviderPolicy(true, Set.of("key-active")),
                        "provider-disabled", new ImmutableProviderTrustSnapshot.ProviderPolicy(false, Set.of("key-old"))
                )
        );

        assertTrue(snapshot.permits("provider-active", "key-active"));
        assertFalse(snapshot.permits("provider-active", "key-unknown"));
        assertFalse(snapshot.permits("provider-disabled", "key-old"));
        assertFalse(snapshot.permits("provider-unknown", "key-active"));
    }
}
