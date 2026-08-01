package com.bbororo.rtb.dsp.auction;

import static com.bbororo.rtb.dsp.contract.ContractChecks.immutableList;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requireAfter;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requireNonBlank;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requirePositive;

import com.bbororo.rtb.dsp.notification.NotificationMessages.NotificationUrls;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuthenticatedBidRequest;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 입찰 중복 방지와 입찰 조정이 주고받는 메시지다. */
public final class AuctionMessages {

    private AuctionMessages() {
    }

    public record BidRequestKey(String sspId, String requestId) {
        public BidRequestKey {
            sspId = requireNonBlank(sspId, "sspId");
            requestId = requireNonBlank(requestId, "requestId");
        }
    }

    public record BidRequestFingerprint(String value) {
        public BidRequestFingerprint {
            value = requireNonBlank(value, "value");
        }
    }

    public record ExecuteBidOnce(
            BidRequestKey key,
            BidRequestFingerprint fingerprint,
            CoordinateBid command
    ) {
        public ExecuteBidOnce {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(command, "command");
            if (!key.sspId().equals(command.request().sspId())
                    || !key.requestId().equals(command.request().request().id())) {
                throw new IllegalArgumentException("key must identify the bid request");
            }
        }
    }

    public record CoordinateBid(AuthenticatedBidRequest request, Instant deadline) {
        public CoordinateBid {
            Objects.requireNonNull(request, "request");
            requireAfter(request.receivedAt(), deadline, "deadline");
        }
    }

    public record BidDecision(String requestId, List<PreparedBid> bids) {
        public BidDecision {
            requestId = requireNonBlank(requestId, "requestId");
            bids = immutableList(bids, "bids");
            var impressionIds = new HashSet<String>();
            for (PreparedBid bid : bids) {
                if (!impressionIds.add(bid.impressionId())) {
                    throw new IllegalArgumentException("bids must not repeat an impressionId");
                }
            }
        }
    }

    public record PreparedBid(
            String bidId,
            String impressionId,
            String campaignId,
            String creativeId,
            long cpmMilliKrw,
            NotificationUrls notificationUrls
    ) {
        public PreparedBid {
            bidId = requireNonBlank(bidId, "bidId");
            impressionId = requireNonBlank(impressionId, "impressionId");
            campaignId = requireNonBlank(campaignId, "campaignId");
            creativeId = requireNonBlank(creativeId, "creativeId");
            requirePositive(cpmMilliKrw, "cpmMilliKrw");
            Objects.requireNonNull(notificationUrls, "notificationUrls");
        }
    }

    public sealed interface BidExecutionResult permits BidExecuted, BidExecutionRejected {
    }

    public record BidExecuted(BidDecision decision, ExecutionKind kind) implements BidExecutionResult {
        public BidExecuted {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(kind, "kind");
        }
    }

    public record BidExecutionRejected(BidExecutionRejection reason) implements BidExecutionResult {
        public BidExecutionRejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public enum ExecutionKind {
        FIRST,
        REUSED
    }

    public enum BidExecutionRejection {
        REQUEST_CONFLICT,
        OWNERSHIP_UNKNOWN,
        CAPACITY_EXCEEDED
    }
}
