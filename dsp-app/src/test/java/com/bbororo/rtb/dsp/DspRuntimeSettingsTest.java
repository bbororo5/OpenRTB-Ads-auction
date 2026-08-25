package com.bbororo.rtb.dsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DspRuntimeSettingsTest {

    @Test
    void parsesDefaultsAndRequiredDeploymentIdentity() {
        var settings = DspRuntimeSettings.fromEnvironment(Map.of(
                "DSP_REGION_ID", "ap-northeast-2"
        ));

        assertEquals(8081, settings.server().port());
        assertEquals("/openrtb/2.6/bid", settings.server().bidPath());
        assertEquals(Duration.ofMillis(180), settings.server().requestTimeout());
        assertEquals("/notices/billing", settings.notices().billingPath());
        assertEquals(4, settings.notices().noticeWorkers());
        assertEquals("ap-northeast-2", settings.regionId());
        assertEquals(100_000, settings.executionMaximumEntries());
    }

    @Test
    void rejectsMissingIdentityAndImpossibleDeadlinePolicy() {
        assertThrows(IllegalStateException.class,
                () -> DspRuntimeSettings.fromEnvironment(Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> DspRuntimeSettings.fromEnvironment(Map.of(
                        "DSP_REGION_ID", "region-1",
                        "DSP_REQUEST_TIMEOUT_MS", "4",
                        "DSP_CANDIDATE_ATTEMPT_COST_MS", "2",
                        "DSP_PUBLICATION_RESERVE_MS", "2"
                )));
    }
}
