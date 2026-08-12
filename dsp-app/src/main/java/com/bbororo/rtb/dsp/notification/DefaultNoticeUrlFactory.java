package com.bbororo.rtb.dsp.notification;

import com.bbororo.rtb.dsp.notification.NoticeIssuanceMessages.NoticeUrl;
import com.bbororo.rtb.dsp.notification.NoticeIssuanceMessages.SealedReservationNotice;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoticeKind;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** 배포 환경의 공개 기준 주소를 OpenRTB 통지 종류별 주소로 확장한다. */
public final class DefaultNoticeUrlFactory implements NoticeUrlFactory {

    private static final Map<NoticeKind, String> PATHS = Map.of(
            NoticeKind.WIN, "notices/win",
            NoticeKind.LOSS, "notices/loss",
            NoticeKind.BILLING, "notices/billing"
    );

    private final String publicBaseUrl;

    public DefaultNoticeUrlFactory(URI publicBaseUri) {
        this.publicBaseUrl = normalizeBaseUri(publicBaseUri);
    }

    @Override
    public NoticeUrl create(SealedReservationNotice notice) {
        Objects.requireNonNull(notice, "notice");
        String path = PATHS.get(notice.kind());
        if (path == null) {
            throw new NoticeIssuanceException("unsupported notice kind: " + notice.kind());
        }
        String token = URLEncoder.encode(notice.encodedToken(), StandardCharsets.UTF_8);
        return new NoticeUrl(
                notice.kind(),
                URI.create(publicBaseUrl + path + "?token=" + token)
        );
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
