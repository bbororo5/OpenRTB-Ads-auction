package com.bbororo.rtb.ssp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SspRuntimeSettingsTest {

    @Test
    void parsesIndependentDspEndpointsAndAeadKey() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);

        SspRuntimeSettings settings = SspRuntimeSettings.fromEnvironment(Map.of(
                "DSP_ENDPOINTS", "project=http://project.test/bid,competitor=http://other.test/bid",
                "RENDER_PROOF_KEY_BASE64", key
        ));

        assertEquals(8080, settings.serverPort());
        assertEquals(URI.create("http://project.test/bid"), settings.dspEndpoints().get("project"));
        assertEquals(32, settings.renderProofKey().length);
    }

    @Test
    void rejectsAnInvalidAesKeyLength() {
        assertThrows(IllegalArgumentException.class, () -> SspRuntimeSettings.fromEnvironment(Map.of(
                "DSP_ENDPOINTS", "project=http://project.test/bid",
                "RENDER_PROOF_KEY_BASE64", Base64.getEncoder().encodeToString(new byte[10])
        )));
    }
}
