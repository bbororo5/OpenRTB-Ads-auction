package com.bbororo.rtb.dsp;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** DB·캠페인·키·배경 작업자를 만드는 데 필요한 8B 운영 설정이다. */
public record DspOperationalSettings(
        String instanceId,
        URI publicBaseUri,
        CampaignSnapshotFile campaignSnapshot,
        ProofKeys proofKeys,
        JdbcStore ledgerStore,
        JdbcStore outcomeStore,
        int jdbcWorkers,
        LeasePolicy leasePolicy,
        Duration expirationRetryDelay
) {

    public DspOperationalSettings {
        instanceId = requireNonBlank(instanceId, "instanceId");
        publicBaseUri = requireHttpBaseUri(publicBaseUri);
        Objects.requireNonNull(campaignSnapshot, "campaignSnapshot");
        Objects.requireNonNull(proofKeys, "proofKeys");
        Objects.requireNonNull(ledgerStore, "ledgerStore");
        Objects.requireNonNull(outcomeStore, "outcomeStore");
        if (jdbcWorkers <= 0 || jdbcWorkers > 256) {
            throw new IllegalArgumentException("jdbcWorkers must be between 1 and 256");
        }
        Objects.requireNonNull(leasePolicy, "leasePolicy");
        requireNonNegative(expirationRetryDelay, "expirationRetryDelay");
    }

    public static DspOperationalSettings fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String commonUsername = required(environment, "DSP_STORE_USERNAME");
        String commonPassword = required(environment, "DSP_STORE_PASSWORD");
        return new DspOperationalSettings(
                required(environment, "DSP_INSTANCE_ID"),
                URI.create(required(environment, "DSP_PUBLIC_BASE_URL")),
                new CampaignSnapshotFile(
                        Path.of(required(environment, "DSP_CAMPAIGN_SNAPSHOT_PATH")),
                        required(environment, "DSP_CAMPAIGN_VERSION"),
                        required(environment, "DSP_CAMPAIGN_SHA256")
                ),
                parseProofKeys(environment),
                new JdbcStore(
                        required(environment, "DSP_LEDGER_JDBC_URL"),
                        environment.getOrDefault("DSP_LEDGER_USERNAME", commonUsername),
                        environment.getOrDefault("DSP_LEDGER_PASSWORD", commonPassword),
                        integer(environment, "DSP_LEDGER_POOL_SIZE", 8)
                ),
                new JdbcStore(
                        required(environment, "DSP_OUTCOME_JDBC_URL"),
                        environment.getOrDefault("DSP_OUTCOME_USERNAME", commonUsername),
                        environment.getOrDefault("DSP_OUTCOME_PASSWORD", commonPassword),
                        integer(environment, "DSP_OUTCOME_POOL_SIZE", 8)
                ),
                integer(environment, "DSP_JDBC_WORKERS", 8),
                new LeasePolicy(
                        millis(environment, "DSP_LEASE_DURATION_MS", 5_000),
                        millis(environment, "DSP_PACING_COVERAGE_MS", 300_000),
                        millis(environment, "DSP_MAX_RESERVATION_LIFETIME_MS", 2_000),
                        millis(environment, "DSP_EVENT_VISIBILITY_MARGIN_MS", 100),
                        positiveLong(environment, "DSP_MINIMUM_LEASE_MICROS", 1_000),
                        positiveLong(environment, "DSP_MAXIMUM_LEASE_MICROS", 100_000),
                        millis(environment, "DSP_LEASE_MAINTENANCE_INTERVAL_MS", 1_000),
                        millis(environment, "DSP_LEASE_DEMAND_COVERAGE_MS", 5_000),
                        integer(environment, "DSP_SETTLEMENT_BATCH_SIZE", 100),
                        millis(environment, "DSP_SETTLEMENT_CLAIM_MS", 5_000)
                ),
                millisAllowZero(environment, "DSP_EXPIRATION_RETRY_MS", 100)
        );
    }

    private static ProofKeys parseProofKeys(Map<String, String> environment) {
        String activeKeyId = required(environment, "DSP_NOTICE_TOKEN_ACTIVE_KEY_ID");
        Map<String, byte[]> keys = new LinkedHashMap<>();
        for (String item : required(environment, "DSP_NOTICE_TOKEN_KEYS").split(",")) {
            String[] pair = item.trim().split("=", 2);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) {
                throw new IllegalArgumentException(
                        "DSP_NOTICE_TOKEN_KEYS must use key-id=base64 entries");
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(pair[1]);
            } catch (IllegalArgumentException invalidBase64) {
                throw new IllegalArgumentException(
                        "DSP_NOTICE_TOKEN_KEYS contains invalid base64", invalidBase64);
            }
            if (keys.put(pair[0], decoded) != null) {
                throw new IllegalArgumentException(
                        "DSP_NOTICE_TOKEN_KEYS must not repeat a key id");
            }
        }
        return new ProofKeys(activeKeyId, keys);
    }

    public record CampaignSnapshotFile(Path path, String requiredVersion, String sha256Hex) {
        public CampaignSnapshotFile {
            Objects.requireNonNull(path, "path");
            requiredVersion = requireNonBlank(requiredVersion, "requiredVersion");
            sha256Hex = requireNonBlank(sha256Hex, "sha256Hex").toLowerCase();
            if (!sha256Hex.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256Hex must be a SHA-256 digest");
            }
        }
    }

    public record ProofKeys(String activeKeyId, Map<String, byte[]> keyRing) {
        public ProofKeys {
            activeKeyId = requireNonBlank(activeKeyId, "activeKeyId");
            Objects.requireNonNull(keyRing, "keyRing");
            Map<String, byte[]> copied = new LinkedHashMap<>();
            keyRing.forEach((keyId, key) -> {
                String validatedId = requireNonBlank(keyId, "keyId");
                byte[] encoded = Objects.requireNonNull(key, "key").clone();
                if (encoded.length != 32) {
                    throw new IllegalArgumentException(
                            "notice token keys must contain 256 bits");
                }
                copied.put(validatedId, encoded);
            });
            keyRing = Map.copyOf(copied);
            if (!keyRing.containsKey(activeKeyId)) {
                throw new IllegalArgumentException("keyRing must contain activeKeyId");
            }
        }

        @Override
        public Map<String, byte[]> keyRing() {
            Map<String, byte[]> copied = new LinkedHashMap<>();
            keyRing.forEach((keyId, key) -> copied.put(keyId, key.clone()));
            return Map.copyOf(copied);
        }
    }

    public record JdbcStore(
            String jdbcUrl,
            String username,
            String password,
            int maximumPoolSize
    ) {
        public JdbcStore {
            jdbcUrl = requireNonBlank(jdbcUrl, "jdbcUrl");
            username = requireNonBlank(username, "username");
            password = requireNonBlank(password, "password");
            if (maximumPoolSize <= 0 || maximumPoolSize > 256) {
                throw new IllegalArgumentException(
                        "maximumPoolSize must be between 1 and 256");
            }
        }
    }

    public record LeasePolicy(
            Duration leaseDuration,
            Duration pacingCoverage,
            Duration maximumReservationLifetime,
            Duration eventVisibilityMargin,
            long minimumLeaseMicros,
            long maximumLeaseMicros,
            Duration maintenanceInterval,
            Duration demandCoverage,
            int settlementBatchSize,
            Duration settlementClaimDuration
    ) {
        public LeasePolicy {
            requirePositive(leaseDuration, "leaseDuration");
            requirePositive(pacingCoverage, "pacingCoverage");
            requirePositive(maximumReservationLifetime, "maximumReservationLifetime");
            requirePositive(eventVisibilityMargin, "eventVisibilityMargin");
            if (minimumLeaseMicros <= 0 || maximumLeaseMicros < minimumLeaseMicros) {
                throw new IllegalArgumentException("lease bounds are invalid");
            }
            requirePositive(maintenanceInterval, "maintenanceInterval");
            requirePositive(demandCoverage, "demandCoverage");
            if (settlementBatchSize <= 0) {
                throw new IllegalArgumentException("settlementBatchSize must be positive");
            }
            requirePositive(settlementClaimDuration, "settlementClaimDuration");
        }
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable %s is required".formatted(name));
        }
        return value;
    }

    private static int integer(Map<String, String> environment, String name, int defaultValue) {
        return Integer.parseInt(environment.getOrDefault(name, Integer.toString(defaultValue)));
    }

    private static long positiveLong(
            Map<String, String> environment,
            String name,
            long defaultValue
    ) {
        long value = Long.parseLong(environment.getOrDefault(name, Long.toString(defaultValue)));
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration millis(
            Map<String, String> environment,
            String name,
            long defaultValue
    ) {
        Duration value = millisAllowZero(environment, name, defaultValue);
        requirePositive(value, name);
        return value;
    }

    private static Duration millisAllowZero(
            Map<String, String> environment,
            String name,
            long defaultValue
    ) {
        long value = Long.parseLong(environment.getOrDefault(name, Long.toString(defaultValue)));
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return Duration.ofMillis(value);
    }

    private static URI requireHttpBaseUri(URI uri) {
        Objects.requireNonNull(uri, "publicBaseUri");
        if (!("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "publicBaseUri must be an HTTP URL without query or fragment");
        }
        return uri;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
