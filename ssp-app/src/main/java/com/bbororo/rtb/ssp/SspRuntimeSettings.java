package com.bbororo.rtb.ssp;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** 배포 환경이 SSP 런타임에 주입하는 비밀값과 외부 주소를 검증한다. */
public record SspRuntimeSettings(
        int serverPort,
        String regionId,
        URI renderCompletionUrl,
        Map<String, URI> dspEndpoints,
        Duration dspBidTimeout,
        int dspMaxInFlight,
        int dspMaxResponseBytes,
        byte renderProofKeyId,
        Map<Byte, byte[]> renderProofKeys,
        Duration billingWorkerInterval,
        Duration noticeTimeout
) {

    public SspRuntimeSettings {
        if (regionId == null || regionId.isBlank()) {
            throw new IllegalArgumentException("regionId must not be blank");
        }
        renderCompletionUrl = requireHttpUrl(renderCompletionUrl, "renderCompletionUrl");
        dspEndpoints = Map.copyOf(dspEndpoints);
        if (dspEndpoints.isEmpty()) {
            throw new IllegalArgumentException("dspEndpoints must include at least one DSP");
        }
        dspEndpoints.forEach((dspId, endpoint) -> {
            if (dspId == null || dspId.isBlank()) {
                throw new IllegalArgumentException("DSP id must not be blank");
            }
            requireHttpUrl(endpoint, "DSP endpoint");
        });
        requirePositive(dspBidTimeout, "dspBidTimeout");
        if (dspBidTimeout.compareTo(Duration.ofMillis(180)) >= 0) {
            throw new IllegalArgumentException("dspBidTimeout must be shorter than 180 ms");
        }
        if (dspMaxInFlight <= 0) {
            throw new IllegalArgumentException("dspMaxInFlight must be positive");
        }
        if (dspMaxResponseBytes < 1_024 || dspMaxResponseBytes > 1_048_576) {
            throw new IllegalArgumentException(
                    "dspMaxResponseBytes must be between 1024 and 1048576"
            );
        }
        renderProofKeys = copyKeys(renderProofKeys);
        if (!renderProofKeys.containsKey(renderProofKeyId)) {
            throw new IllegalArgumentException("renderProofKeys must contain the active key id");
        }
    }

    @Override
    public Map<Byte, byte[]> renderProofKeys() {
        return copyKeys(renderProofKeys);
    }

    public static SspRuntimeSettings fromEnvironment(Map<String, String> environment) {
        int port = Integer.parseInt(environment.getOrDefault("SERVER_PORT", "8080"));
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("SERVER_PORT must be between 1 and 65535");
        }
        String regionId = required("SSP_REGION_ID", environment);
        URI renderCompletionUrl = requireHttpUrl(
                URI.create(required("RENDER_COMPLETION_URL", environment)),
                "RENDER_COMPLETION_URL"
        );
        Map<String, URI> endpoints = parseEndpoints(required("DSP_ENDPOINTS", environment));
        Duration bidTimeout = Duration.ofMillis(
                Long.parseLong(environment.getOrDefault("DSP_BID_TIMEOUT_MS", "35"))
        );
        int maxInFlight = Integer.parseInt(
                environment.getOrDefault("DSP_MAX_IN_FLIGHT", "64")
        );
        int maxResponseBytes = Integer.parseInt(
                environment.getOrDefault("DSP_MAX_RESPONSE_BYTES", "65536")
        );
        byte keyId = Byte.parseByte(environment.getOrDefault("RENDER_PROOF_KEY_ID", "1"));
        Map<Byte, byte[]> keys = parseRenderProofKeys(environment, keyId);
        Duration workerInterval = Duration.ofMillis(
                Long.parseLong(environment.getOrDefault("BILLING_WORKER_INTERVAL_MS", "10"))
        );
        Duration noticeTimeout = Duration.ofMillis(
                Long.parseLong(environment.getOrDefault("DSP_NOTICE_TIMEOUT_MS", "500"))
        );
        return new SspRuntimeSettings(
                port,
                regionId,
                renderCompletionUrl,
                endpoints,
                bidTimeout,
                maxInFlight,
                maxResponseBytes,
                keyId,
                keys,
                workerInterval,
                noticeTimeout
        );
    }

    private static Map<Byte, byte[]> parseRenderProofKeys(
            Map<String, String> environment,
            byte activeKeyId
    ) {
        String keyRing = environment.get("RENDER_PROOF_KEYS");
        if (keyRing == null || keyRing.isBlank()) {
            return Map.of(
                    activeKeyId,
                    decodeAesKey(required("RENDER_PROOF_KEY_BASE64", environment))
            );
        }
        Map<Byte, byte[]> keys = new LinkedHashMap<>();
        for (String item : keyRing.split(",")) {
            String[] pair = item.trim().split("=", 2);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) {
                throw new IllegalArgumentException(
                        "RENDER_PROOF_KEYS must use key-id=base64 entries"
                );
            }
            byte keyId = Byte.parseByte(pair[0]);
            if (keys.put(keyId, decodeAesKey(pair[1])) != null) {
                throw new IllegalArgumentException("RENDER_PROOF_KEYS must not repeat a key id");
            }
        }
        if (!keys.containsKey(activeKeyId)) {
            throw new IllegalArgumentException("RENDER_PROOF_KEYS must contain the active key id");
        }
        return keys;
    }

    private static byte[] decodeAesKey(String encoded) {
        byte[] key = Base64.getDecoder().decode(encoded);
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            throw new IllegalArgumentException(
                    "Render proof keys must contain 128, 192, or 256 bits"
            );
        }
        return key;
    }

    private static Map<String, URI> parseEndpoints(String value) {
        Map<String, URI> endpoints = new LinkedHashMap<>();
        for (String item : value.split(",")) {
            String[] pair = item.trim().split("=", 2);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) {
                throw new IllegalArgumentException("DSP_ENDPOINTS must use dsp-id=https://host/path entries");
            }
            URI previous = endpoints.put(pair[0], URI.create(pair[1]));
            if (previous != null) {
                throw new IllegalArgumentException("DSP_ENDPOINTS must not repeat a dsp id");
            }
        }
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("DSP_ENDPOINTS must include at least one DSP");
        }
        return endpoints;
    }

    private static String required(String name, Map<String, String> environment) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable %s is required".formatted(name));
        }
        return value;
    }

    private static URI requireHttpUrl(URI value, String name) {
        if (value == null
                || !value.isAbsolute()
                || (!"http".equalsIgnoreCase(value.getScheme())
                && !"https".equalsIgnoreCase(value.getScheme()))) {
            throw new IllegalArgumentException(name + " must be an absolute HTTP URL");
        }
        return value;
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static Map<Byte, byte[]> copyKeys(Map<Byte, byte[]> source) {
        Map<Byte, byte[]> copy = new LinkedHashMap<>();
        source.forEach((keyId, key) -> copy.put(keyId, key.clone()));
        return Map.copyOf(copy);
    }
}
