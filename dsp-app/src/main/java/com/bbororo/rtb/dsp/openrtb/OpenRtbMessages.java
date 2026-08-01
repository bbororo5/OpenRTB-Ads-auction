package com.bbororo.rtb.dsp.openrtb;

import static com.bbororo.rtb.dsp.contract.ContractChecks.immutableList;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requireNonBlank;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requireNonNegative;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requirePositive;

import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 프로젝트 DSP가 소유하는 OpenRTB 2.6 하위 규격의 내부 표현이다. */
public final class OpenRtbMessages {

    public static final int RENDER_EXPIRY_SECONDS = 2;

    private OpenRtbMessages() {
    }

    public record AuthenticatedBidRequest(String sspId, BidRequest request, Instant receivedAt) {
        public AuthenticatedBidRequest {
            sspId = requireNonBlank(sspId, "sspId");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(receivedAt, "receivedAt");
        }
    }

    public record BidRequest(String id, int tmaxMillis, List<Impression> impressions) {
        public BidRequest {
            id = requireNonBlank(id, "id");
            if (tmaxMillis <= 0 || tmaxMillis > 180) {
                throw new IllegalArgumentException("tmaxMillis must be between 1 and 180");
            }
            impressions = immutableList(impressions, "impressions");
            if (impressions.isEmpty()) {
                throw new IllegalArgumentException("impressions must not be empty");
            }
            var ids = new HashSet<String>();
            for (Impression impression : impressions) {
                if (!ids.add(impression.id())) {
                    throw new IllegalArgumentException("impressions must not repeat an id");
                }
            }
        }
    }

    public record Impression(
            String id,
            int width,
            int height,
            long bidFloorCpmMilliKrw,
            int expirySeconds
    ) {
        public Impression {
            id = requireNonBlank(id, "id");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("width and height must be positive");
            }
            requireNonNegative(bidFloorCpmMilliKrw, "bidFloorCpmMilliKrw");
            if (expirySeconds != RENDER_EXPIRY_SECONDS) {
                throw new IllegalArgumentException("expirySeconds must be 2");
            }
        }
    }

    public sealed interface BidResult permits BidResponse, NoBid {
    }

    public record BidResponse(String requestId, List<Bid> bids) implements BidResult {
        public BidResponse {
            requestId = requireNonBlank(requestId, "requestId");
            bids = immutableList(bids, "bids");
            if (bids.isEmpty()) {
                throw new IllegalArgumentException("bids must not be empty");
            }
            var impressionIds = new HashSet<String>();
            for (Bid bid : bids) {
                if (!impressionIds.add(bid.impressionId())) {
                    throw new IllegalArgumentException("bids must not repeat an impressionId");
                }
            }
        }
    }

    public record NoBid(String requestId, NoBidReason reason) implements BidResult {
        public NoBid {
            requestId = requireNonBlank(requestId, "requestId");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record Bid(
            String bidId,
            String impressionId,
            String campaignId,
            String creativeId,
            long cpmMilliKrw,
            URI noticeUrl,
            URI lossUrl,
            URI billingUrl,
            int expirySeconds
    ) {
        public Bid {
            bidId = requireNonBlank(bidId, "bidId");
            impressionId = requireNonBlank(impressionId, "impressionId");
            campaignId = requireNonBlank(campaignId, "campaignId");
            creativeId = requireNonBlank(creativeId, "creativeId");
            requirePositive(cpmMilliKrw, "cpmMilliKrw");
            noticeUrl = requireHttpUrl(noticeUrl, "noticeUrl");
            lossUrl = requireHttpUrl(lossUrl, "lossUrl");
            billingUrl = requireHttpUrl(billingUrl, "billingUrl");
            if (expirySeconds != RENDER_EXPIRY_SECONDS) {
                throw new IllegalArgumentException("expirySeconds must be 2");
            }
        }
    }

    public record AuctionNotice(String sspId, NoticeKind kind, String opaqueToken, Instant receivedAt) {
        public AuctionNotice {
            sspId = requireNonBlank(sspId, "sspId");
            Objects.requireNonNull(kind, "kind");
            opaqueToken = requireNonBlank(opaqueToken, "opaqueToken");
            Objects.requireNonNull(receivedAt, "receivedAt");
        }
    }

    public enum NoticeKind {
        WIN,
        LOSS,
        BILLING
    }

    public enum NoBidReason {
        NO_ELIGIBLE_CAMPAIGN,
        NO_LOCAL_BUDGET,
        DEADLINE_EXCEEDED,
        DUPLICATE_CONFLICT,
        OWNERSHIP_UNKNOWN,
        NOT_READY
    }

    public enum NoticeHttpResult {
        ACCEPTED,
        INVALID,
        TEMPORARILY_UNAVAILABLE
    }

    private static URI requireHttpUrl(URI uri, String name) {
        Objects.requireNonNull(uri, name);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException(name + " must be an HTTP URL");
        }
        return uri;
    }
}
