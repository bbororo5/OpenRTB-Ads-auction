package com.bbororo.rtb.ssp.contract;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** OpenRTB 통지 매크로를 사건 시점까지 보존하는 검증된 HTTP URL 템플릿이다. */
public record NoticeUrlTemplate(String value) {

    private static final Pattern MACRO = Pattern.compile("\\$\\{[A-Z0-9_]+}");

    public NoticeUrlTemplate {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("notice URL template must not be blank");
        }
        URI structuralUri = URI.create(MACRO.matcher(value).replaceAll("macro"));
        if (!structuralUri.isAbsolute()
                || (!"http".equalsIgnoreCase(structuralUri.getScheme())
                && !"https".equalsIgnoreCase(structuralUri.getScheme()))) {
            throw new IllegalArgumentException("notice URL template must be an absolute HTTP URL");
        }
    }

    public NoticeUrlTemplate(URI value) {
        this(Objects.requireNonNull(value, "value").toString());
    }

    public URI render(Context context) {
        Objects.requireNonNull(context, "context");
        String rendered = value
                .replace("${AUCTION_ID}", encode(context.auctionId()))
                .replace("${AUCTION_IMP_ID}", encode(context.impressionId()))
                .replace("${AUCTION_PRICE}", price(context.cpmMilliKrw()))
                .replace("${AUCTION_CURRENCY}", "KRW")
                .replace("${AUCTION_LOSS}", context.lossReason() == null
                        ? "" : Integer.toString(context.lossReason()))
                .replace("${AUCTION_IMP_TS}", context.impressionAt() == null
                        ? "" : Long.toString(context.impressionAt().toEpochMilli()));
        rendered = MACRO.matcher(rendered).replaceAll("");
        return URI.create(rendered);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String price(Long cpmMilliKrw) {
        return cpmMilliKrw == null
                ? ""
                : KrwCpm.fromMilliKrw(cpmMilliKrw).stripTrailingZeros().toPlainString();
    }

    public record Context(
            String auctionId,
            String impressionId,
            Long cpmMilliKrw,
            Integer lossReason,
            Instant impressionAt
    ) {
        public Context {
            if (auctionId == null || auctionId.isBlank()) {
                throw new IllegalArgumentException("auctionId must not be blank");
            }
            if (impressionId == null || impressionId.isBlank()) {
                throw new IllegalArgumentException("impressionId must not be blank");
            }
            if (cpmMilliKrw != null && cpmMilliKrw <= 0) {
                throw new IllegalArgumentException("cpmMilliKrw must be positive");
            }
            if (lossReason != null && lossReason < 0) {
                throw new IllegalArgumentException("lossReason must not be negative");
            }
        }
    }
}
