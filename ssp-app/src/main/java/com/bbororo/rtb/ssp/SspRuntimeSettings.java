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
        byte renderProofKeyId,
        byte[] renderProofKey,
        Duration billingWorkerInterval,
        Duration noticeTimeout
) {

    public SspRuntimeSettings {
        if (regionId == null || regionId.isBlank()) {
            throw new IllegalArgumentException("regionId must not be blank");
        }
        renderCompletionUrl = requireHttpUrl(renderCompletionUrl, "renderCompletionUrl");
        dspEndpoints = Map.copyOf(dspEndpoints);
        renderProofKey = renderProofKey.clone();
    }

    @Override
    public byte[] renderProofKey() {
        return renderProofKey.clone();
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
        byte keyId = Byte.parseByte(environment.getOrDefault("RENDER_PROOF_KEY_ID", "1"));
        byte[] key = Base64.getDecoder().decode(required("RENDER_PROOF_KEY_BASE64", environment));
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            throw new IllegalArgumentException("RENDER_PROOF_KEY_BASE64 must contain a 128, 192, or 256-bit AES key");
        }
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
                keyId,
                key,
                workerInterval,
                noticeTimeout
        );
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
}
