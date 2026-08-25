package com.bbororo.rtb.dsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DspOperationalSettingsTest {

    @Test
    void parsesRequiredStoresSnapshotAndProofKeyRing() {
        var settings = DspOperationalSettings.fromEnvironment(environment());

        assertEquals("dsp-seoul-1", settings.instanceId());
        assertEquals(URI.create("https://dsp.example/"), settings.publicBaseUri());
        assertEquals("v7", settings.campaignSnapshot().requiredVersion());
        assertEquals(32, settings.proofKeys().keyRing().get("key-7").length);
        assertEquals(8, settings.ledgerStore().maximumPoolSize());
        assertEquals(Duration.ofSeconds(1), settings.leasePolicy().maintenanceInterval());
    }

    @Test
    void rejectsMissingSecretsAndNonAes256Keys() {
        assertThrows(IllegalStateException.class,
                () -> DspOperationalSettings.fromEnvironment(Map.of()));
        var environment = environment();
        environment.put("DSP_NOTICE_TOKEN_KEYS",
                "key-7=" + Base64.getEncoder().encodeToString(new byte[16]));
        assertThrows(IllegalArgumentException.class,
                () -> DspOperationalSettings.fromEnvironment(environment));
    }

    private static Map<String, String> environment() {
        var environment = new HashMap<String, String>();
        environment.put("DSP_INSTANCE_ID", "dsp-seoul-1");
        environment.put("DSP_PUBLIC_BASE_URL", "https://dsp.example/");
        environment.put("DSP_CAMPAIGN_SNAPSHOT_PATH", "/config/campaigns.json");
        environment.put("DSP_CAMPAIGN_VERSION", "v7");
        environment.put("DSP_CAMPAIGN_SHA256", "a".repeat(64));
        environment.put("DSP_NOTICE_TOKEN_ACTIVE_KEY_ID", "key-7");
        environment.put("DSP_NOTICE_TOKEN_KEYS",
                "key-7=" + Base64.getEncoder().encodeToString(new byte[32]));
        environment.put("DSP_LEDGER_JDBC_URL", "jdbc:postgresql://ledger/rtb");
        environment.put("DSP_OUTCOME_JDBC_URL", "jdbc:postgresql://outcome/rtb");
        environment.put("DSP_STORE_USERNAME", "dsp");
        environment.put("DSP_STORE_PASSWORD", "secret");
        return environment;
    }
}
