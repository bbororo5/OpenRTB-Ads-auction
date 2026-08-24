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
import java.util.OptionalInt;

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

    /** HTTP 200 응답 본문 또는 HTTP 204 무본문을 나타내는 입찰 전송 결과다. */
    public sealed interface BidHttpResult permits BidResponse, NoContent {
    }

    /** 실제 입찰의 seatbid 또는 전체 무입찰 사유 nbr 중 정확히 하나를 담는다. */
    public record BidResponse(
            String id,
            List<SeatBid> seatbid,
            OptionalInt nbr
    ) implements BidHttpResult {

        public BidResponse {
            id = requireNonBlank(id, "id");
            seatbid = immutableList(seatbid, "seatbid");
            Objects.requireNonNull(nbr, "nbr");
            if (nbr.isPresent() && nbr.getAsInt() < 0) {
                throw new IllegalArgumentException("nbr must not be negative");
            }
            if (seatbid.isEmpty() == nbr.isEmpty()) {
                throw new IllegalArgumentException(
                        "BidResponse must contain either seatbid or nbr, but not both"
                );
            }
        }

        public static BidResponse withBids(String id, List<SeatBid> seatbid) {
            return new BidResponse(id, seatbid, OptionalInt.empty());
        }

        public static BidResponse noBid(String id, int nbr) {
            return new BidResponse(id, List.of(), OptionalInt.of(nbr));
        }
    }

    /** OpenRTB 응답 본문 없이 HTTP 204를 반환한다. */
    public enum NoContent implements BidHttpResult {
        INSTANCE
    }

    /** 한 구매 seat가 제출하는 하나 이상의 입찰 묶음이다. */
    public record SeatBid(List<Bid> bids) {
        public SeatBid {
            bids = immutableList(bids, "bids");
            if (bids.isEmpty()) {
                throw new IllegalArgumentException("bids must not be empty");
            }
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
