package com.bbororo.rtb.dsp.proof.internal;

import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.NoticeUrl;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.SealedReservationNotice;
import com.bbororo.rtb.dsp.proof.api.ReservationNoticeKind;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** 배포 환경의 공개 기준 주소를 OpenRTB 통지 종류별 주소로 확장한다. */
public final class DefaultNoticeUrlFactory implements NoticeUrlFactory {

    private static final Map<ReservationNoticeKind, String> PATHS = Map.of(
            ReservationNoticeKind.WIN, "notices/win",
            ReservationNoticeKind.LOSS, "notices/loss",
            ReservationNoticeKind.BILLING, "notices/billing"
    );

    private final String publicBaseUrl;
    private final Map<ReservationNoticeKind, String> paths;

    public DefaultNoticeUrlFactory(URI publicBaseUri) {
        this(publicBaseUri, PATHS);
    }

    public DefaultNoticeUrlFactory(
            URI publicBaseUri,
            Map<ReservationNoticeKind, String> paths
    ) {
        this.publicBaseUrl = normalizeBaseUri(publicBaseUri);
        Objects.requireNonNull(paths, "paths");
        var normalized = new java.util.EnumMap<ReservationNoticeKind, String>(
                ReservationNoticeKind.class);
        for (ReservationNoticeKind kind : ReservationNoticeKind.values()) {
            normalized.put(kind, normalizePath(paths.get(kind), kind));
        }
        this.paths = Map.copyOf(normalized);
    }

    @Override
    public NoticeUrl create(SealedReservationNotice notice) {
        Objects.requireNonNull(notice, "notice");
        String path = paths.get(notice.kind());
        if (path == null) {
            throw new NoticeIssuanceException("unsupported notice kind: " + notice.kind());
        }
        String token = URLEncoder.encode(notice.encodedToken(), StandardCharsets.UTF_8);
        return new NoticeUrl(
                notice.kind(),
                URI.create(publicBaseUrl + path + "?token=" + token)
        );
    }

    private static String normalizePath(String path, ReservationNoticeKind kind) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("missing notice path for " + kind);
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (normalized.isBlank() || normalized.contains("?") || normalized.contains("#")) {
            throw new IllegalArgumentException("invalid notice path for " + kind);
        }
        return normalized;
    }

    private static String normalizeBaseUri(URI uri) {
        Objects.requireNonNull(uri, "publicBaseUri");
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "publicBaseUri must be an HTTP URL without query or fragment"
            );
        }
        String value = uri.toASCIIString();
        return value.endsWith("/") ? value : value + '/';
    }
}
