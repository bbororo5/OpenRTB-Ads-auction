package com.bbororo.rtb.dsp;

import com.bbororo.rtb.dsp.openrtb.ArmeriaDspOpenRtbServer;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** 배포 환경의 문자열 설정을 검증된 DSP 실행 정책으로 바꾼다. */
public record DspRuntimeSettings(
        ArmeriaDspOpenRtbServer.Settings server,
        ArmeriaDspOpenRtbServer.NoticeSettings notices,
        String regionId,
        Duration reservationLifetime,
        Duration candidateAttemptCost,
        Duration publicationReserve,
        Duration executionRetention,
        int executionMaximumEntries
) {

    public DspRuntimeSettings(
            ArmeriaDspOpenRtbServer.Settings server,
            String regionId,
            Duration reservationLifetime,
            Duration candidateAttemptCost,
            Duration publicationReserve,
            Duration executionRetention,
            int executionMaximumEntries
    ) {
        this(
                server,
                ArmeriaDspOpenRtbServer.NoticeSettings.defaults(),
                regionId,
                reservationLifetime,
                candidateAttemptCost,
                publicationReserve,
                executionRetention,
                executionMaximumEntries
        );
    }

    public DspRuntimeSettings {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(notices, "notices");
        if (regionId == null || regionId.isBlank()) {
            throw new IllegalArgumentException("regionId must not be blank");
        }
        requirePositive(reservationLifetime, "reservationLifetime");
        requireNonNegative(candidateAttemptCost, "candidateAttemptCost");
        requireNonNegative(publicationReserve, "publicationReserve");
        requirePositive(executionRetention, "executionRetention");
        if (executionMaximumEntries <= 0 || executionMaximumEntries > 1_000_000) {
            throw new IllegalArgumentException(
                    "executionMaximumEntries must be between 1 and 1000000");
        }
        if (candidateAttemptCost.plus(publicationReserve)
                .compareTo(server.requestTimeout()) >= 0) {
            throw new IllegalArgumentException(
                    "bid work threshold must be shorter than server request timeout");
        }
    }

    public static DspRuntimeSettings fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        int port = integer(environment, "SERVER_PORT", 8081);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("SERVER_PORT must be between 1 and 65535");
        }
        var server = new ArmeriaDspOpenRtbServer.Settings(
                port,
                environment.getOrDefault("DSP_BID_PATH", "/openrtb/2.6/bid"),
                positiveLong(environment, "DSP_MAX_REQUEST_BYTES", 65_536),
                millis(environment, "DSP_REQUEST_TIMEOUT_MS", 180),
                millisAllowZero(environment, "DSP_GRACEFUL_QUIET_MS", 100),
                millis(environment, "DSP_GRACEFUL_TIMEOUT_MS", 1_000),
                integer(environment, "DSP_BID_WORKERS", 8)
        );
        var notices = new ArmeriaDspOpenRtbServer.NoticeSettings(
                environment.getOrDefault("DSP_WIN_NOTICE_PATH", "/notices/win"),
                environment.getOrDefault("DSP_LOSS_NOTICE_PATH", "/notices/loss"),
                environment.getOrDefault("DSP_BILLING_NOTICE_PATH", "/notices/billing"),
                integer(environment, "DSP_NOTICE_WORKERS", 4)
        );
        return new DspRuntimeSettings(
                server,
                notices,
                required(environment, "DSP_REGION_ID"),
                millis(environment, "DSP_RESERVATION_LIFETIME_MS", 2_000),
                millisAllowZero(environment, "DSP_CANDIDATE_ATTEMPT_COST_MS", 2),
                millisAllowZero(environment, "DSP_PUBLICATION_RESERVE_MS", 2),
                millis(environment, "DSP_EXECUTION_RETENTION_MS", 5_000),
                integer(environment, "DSP_EXECUTION_MAX_ENTRIES", 100_000)
        );
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
        if (value <= 0) {
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
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return Duration.ofMillis(value);
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
