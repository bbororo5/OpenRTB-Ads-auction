package com.bbororo.rtb.ssp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SspRuntimeSettingsTest {

    @Test
    void parsesIndependentDspEndpointsAndAeadKeyRing() {
        String oldKey = Base64.getEncoder().encodeToString(new byte[16]);
        String activeKey = Base64.getEncoder().encodeToString(new byte[32]);

        SspRuntimeSettings settings = SspRuntimeSettings.fromEnvironment(Map.of(
                "SSP_REGION_ID", "region-a",
                "RENDER_COMPLETION_URL", "https://region-a.ssp.test/publisher/render",
                "DSP_ENDPOINTS", "project=http://project.test/bid,competitor=http://other.test/bid",
                "RENDER_PROOF_KEY_ID", "8",
                "RENDER_PROOF_KEYS", "7=" + oldKey + ",8=" + activeKey
        ));

        assertEquals(8080, settings.serverPort());
        assertEquals("region-a", settings.regionId());
        assertEquals(
                URI.create("https://region-a.ssp.test/publisher/render"),
                settings.renderCompletionUrl()
        );
        assertEquals(URI.create("http://project.test/bid"), settings.dspEndpoints().get("project"));
        assertEquals(java.time.Duration.ofMillis(35), settings.dspBidTimeout());
        assertEquals(64, settings.dspMaxInFlight());
        assertEquals(65_536, settings.dspMaxResponseBytes());
        assertEquals(10_000, settings.auctionDedupMaximumEntries());
        assertEquals(16, settings.billingWorkerConcurrency());
        assertEquals((byte) 8, settings.renderProofKeyId());
        assertEquals(16, settings.renderProofKeys().get((byte) 7).length);
        assertEquals(32, settings.renderProofKeys().get((byte) 8).length);
        settings.renderProofKeys().get((byte) 8)[0] = 1;
        assertEquals(0, settings.renderProofKeys().get((byte) 8)[0]);
    }

    @Test
    void rejectsAnInvalidAesKeyLength() {
        assertThrows(IllegalArgumentException.class, () -> SspRuntimeSettings.fromEnvironment(Map.of(
                "SSP_REGION_ID", "region-a",
                "RENDER_COMPLETION_URL", "https://region-a.ssp.test/publisher/render",
                "DSP_ENDPOINTS", "project=http://project.test/bid",
                "RENDER_PROOF_KEY_BASE64", Base64.getEncoder().encodeToString(new byte[10])
        )));
    }

    @Test
    void requiresTheActiveKeyInTheConfiguredKeyRing() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);

        assertThrows(IllegalArgumentException.class, () -> SspRuntimeSettings.fromEnvironment(Map.of(
                "SSP_REGION_ID", "region-a",
                "RENDER_COMPLETION_URL", "https://region-a.ssp.test/publisher/render",
                "DSP_ENDPOINTS", "project=http://project.test/bid",
                "RENDER_PROOF_KEY_ID", "8",
                "RENDER_PROOF_KEYS", "7=" + key
        )));
    }

    @Test
    void rejectsAnUnsafeDspBidBoundary() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        Map<String, String> environment = Map.of(
                "SSP_REGION_ID", "region-a",
                "RENDER_COMPLETION_URL", "https://region-a.ssp.test/publisher/render",
                "DSP_ENDPOINTS", "project=http://project.test/bid",
                "DSP_BID_TIMEOUT_MS", "180",
                "RENDER_PROOF_KEY_BASE64", key
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> SspRuntimeSettings.fromEnvironment(environment)
        );
    }

    @Test
    void rejectsAnUnboundedBillingWorkerCount() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);

        assertThrows(IllegalArgumentException.class, () -> SspRuntimeSettings.fromEnvironment(Map.of(
                "SSP_REGION_ID", "region-a",
                "RENDER_COMPLETION_URL", "https://region-a.ssp.test/publisher/render",
                "DSP_ENDPOINTS", "project=http://project.test/bid",
                "BILLING_WORKER_CONCURRENCY", "257",
                "RENDER_PROOF_KEY_BASE64", key
        )));
    }

    @Test
    void rejectsAnUnboundedAuctionDeduplicationCapacity() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);

        assertThrows(IllegalArgumentException.class, () -> SspRuntimeSettings.fromEnvironment(Map.of(
                "SSP_REGION_ID", "region-a",
                "RENDER_COMPLETION_URL", "https://region-a.ssp.test/publisher/render",
                "DSP_ENDPOINTS", "project=http://project.test/bid",
                "AUCTION_DEDUP_MAX_ENTRIES", "1000001",
                "RENDER_PROOF_KEY_BASE64", key
        )));
    }
}
