package com.bbororo.rtb.dsp.campaignruntime.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.RankCampaigns;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Impression;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CampaignComponentFactoryTest {

    @TempDir
    Path directory;

    @Test
    void verifiesAndInstallsAJsonSnapshotBeforeServingCandidates() throws Exception {
        byte[] json = snapshot().getBytes(StandardCharsets.UTF_8);
        Path path = directory.resolve("campaigns.json");
        Files.write(path, json);
        var components = CampaignComponentFactory.createFromJsonFile(
                path, "v1", sha256(json), (campaignId, evaluatedAt) -> 17L);

        var candidates = components.candidates().rankCandidates(new RankCampaigns(
                "auction-1",
                new Impression("imp-1", 300, 250, 1_000, 2),
                Instant.parse("2026-08-25T00:30:00Z")
        ));

        assertEquals(1, candidates.size());
        assertEquals("campaign-1", candidates.getFirst().campaignId());
        assertEquals(17L, candidates.getFirst().pacingLagPpm());
    }

    @Test
    void refusesAFileWhoseDeploymentChecksumDoesNotMatch() throws Exception {
        Path path = directory.resolve("campaigns.json");
        Files.writeString(path, snapshot());

        assertThrows(IllegalStateException.class, () ->
                CampaignComponentFactory.createFromJsonFile(
                        path, "v1", "0".repeat(64), (ignored, at) -> 0L));
    }

    private static String snapshot() {
        return """
                {
                  "version":"v1",
                  "campaigns":[{
                    "id":"campaign-1",
                    "active":true,
                    "bidCpmMilliKrw":2000,
                    "startsAt":"2026-08-25T00:00:00Z",
                    "endsAt":"2026-08-25T01:00:00Z",
                    "creatives":[{"id":"creative-1","width":300,"height":250}]
                  }]
                }
                """;
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
