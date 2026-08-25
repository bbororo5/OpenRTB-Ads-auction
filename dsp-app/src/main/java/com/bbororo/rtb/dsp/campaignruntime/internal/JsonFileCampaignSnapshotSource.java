package com.bbororo.rtb.dsp.campaignruntime.internal;

import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.Campaign;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.CampaignSnapshot;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.Creative;
import com.bbororo.rtb.dsp.campaignruntime.spi.CampaignSnapshotSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 운영자가 배포한 JSON 스냅숏을 SHA-256으로 확인한 뒤 캠페인 계약으로 변환한다. */
public final class JsonFileCampaignSnapshotSource implements CampaignSnapshotSource {

    private final Path path;
    private final String expectedSha256;
    private final ObjectMapper json;

    public JsonFileCampaignSnapshotSource(Path path, String expectedSha256) {
        this(path, expectedSha256, new ObjectMapper());
    }

    JsonFileCampaignSnapshotSource(
            Path path,
            String expectedSha256,
            ObjectMapper json
    ) {
        this.path = Objects.requireNonNull(path, "path");
        this.expectedSha256 = Objects.requireNonNull(expectedSha256, "expectedSha256")
                .toLowerCase();
        if (!this.expectedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expectedSha256 must be a SHA-256 digest");
        }
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public CompletionStage<CampaignSnapshot> load(String requiredVersion) {
        try {
            byte[] encoded = Files.readAllBytes(path);
            String actualSha256 = sha256(encoded);
            if (!expectedSha256.equals(actualSha256)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("campaign snapshot checksum mismatch"));
            }
            CampaignSnapshot snapshot = decode(encoded, actualSha256);
            if (!snapshot.version().equals(requiredVersion)) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "campaign snapshot version mismatch: " + snapshot.version()));
            }
            return CompletableFuture.completedFuture(snapshot);
        } catch (IOException | RuntimeException failure) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "could not load campaign snapshot: " + path, failure));
        }
    }

    private CampaignSnapshot decode(byte[] encoded, String checksum) throws IOException {
        JsonNode root = json.readTree(encoded);
        String version = requiredText(root, "version");
        JsonNode campaignNodes = requiredArray(root, "campaigns");
        List<Campaign> campaigns = new ArrayList<>(campaignNodes.size());
        for (JsonNode campaign : campaignNodes) {
            JsonNode creativeNodes = requiredArray(campaign, "creatives");
            List<Creative> creatives = new ArrayList<>(creativeNodes.size());
            for (JsonNode creative : creativeNodes) {
                creatives.add(new Creative(
                        requiredText(creative, "id"),
                        requiredInt(creative, "width"),
                        requiredInt(creative, "height")
                ));
            }
            campaigns.add(new Campaign(
                    requiredText(campaign, "id"),
                    requiredBoolean(campaign, "active"),
                    requiredLong(campaign, "bidCpmMilliKrw"),
                    Instant.parse(requiredText(campaign, "startsAt")),
                    Instant.parse(requiredText(campaign, "endsAt")),
                    creatives
            ));
        }
        return new CampaignSnapshot(version, checksum, campaigns);
    }

    private static JsonNode requiredArray(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        return value;
    }

    private static String requiredText(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank text");
        }
        return value.textValue();
    }

    private static int requiredInt(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.canConvertToInt()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return value.intValue();
    }

    private static long requiredLong(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.canConvertToLong()) {
            throw new IllegalArgumentException(name + " must be a long");
        }
        return value.longValue();
    }

    private static boolean requiredBoolean(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return value.booleanValue();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", impossible);
        }
    }
}
