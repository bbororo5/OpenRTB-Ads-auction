package com.bbororo.rtb.ssp.contract;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * SSP C3 컴포넌트가 주고받는 내부 메시지다.
 *
 * <p>이 타입은 같은 프로세스의 값 객체이며, 메시지 브로커나 원격 RPC 계약이 아니다.</p>
 */
public final class SspMessages {

    private SspMessages() {
    }

    public record AuctionRequest(
            String providerId,
            String providerKeyId,
            String providerRequestId,
            Instant deadline,
            List<AuctionSlot> slots
    ) {
        public AuctionRequest {
            slots = List.copyOf(slots);
        }

        /** 이 요청이 기존 경매와 같은지를 판단하는 SSP 내부 지문이다. */
        public AuctionRequestFingerprint fingerprint() {
            return AuctionRequestFingerprintCalculator.calculate(this);
        }
    }

    public record AuctionSlot(String impId) {
    }

    public record AuctionResult(String auctionId, List<WinningBid> winners, RenderProof renderProof) {
        public AuctionResult {
            winners = List.copyOf(winners);
        }
    }

    public record WinningBid(
            String slotAuctionKey,
            String dspId,
            String bidId,
            long cpmKrw,
            URI nurl,
            URI lurl,
            URI burl
    ) {
    }

    public record BidRequestBatch(String auctionId, AuctionRequest auction, List<String> dspIds, Instant deadline) {
        public BidRequestBatch {
            dspIds = List.copyOf(dspIds);
        }
    }

    public record DspBid(
            String dspId,
            String impId,
            String bidId,
            long cpmKrw,
            URI nurl,
            URI lurl,
            URI burl
    ) {
    }

    public record BidResponses(List<DspBid> bids) {
        public BidResponses {
            bids = List.copyOf(bids);
        }
    }

    public record EligibleBids(List<DspBid> bids) {
        public EligibleBids {
            bids = List.copyOf(bids);
        }
    }

    public record AuctionWinners(List<WinningBid> winners) {
        public AuctionWinners {
            winners = List.copyOf(winners);
        }
    }

    public record RenderProof(String encodedValue) {
    }

    public record ProofIssuance(
            AuctionRequest auction,
            AuctionWinners winners,
            Instant issuedAt,
            Instant expiresAt
    ) {
    }

    public record RenderCompleted(RenderProof renderProof, Instant receivedAt) {
    }

    public record VerifiedRender(
            String providerId,
            String providerRequestId,
            String impId,
            String slotAuctionKey,
            String proofDigest,
            String dspId,
            URI billingUrl,
            Instant auctionIssuedAt,
            Instant renderExpiresAt
    ) {
    }

    public record BillingClaim(
            String providerId,
            String providerRequestId,
            String impId,
            String slotAuctionKey,
            String proofDigest,
            String dspId,
            URI billingUrl,
            Instant billingDeadline
    ) {
    }

    public record BillingDeliveryTask(String deliveryId, BillingClaim claim) {
    }

    public record DeliveryLease(String deliveryId, long generation, Instant leaseUntil) {
    }

    public record LeasedBillingDelivery(BillingDeliveryTask task, DeliveryLease lease) {
    }

    public record AuctionNotice(NoticeKind kind, URI url) {
    }

    public enum NoticeKind {
        WIN,
        LOSS
    }

    public enum RenderAcceptance {
        ACCEPTED,
        DUPLICATE,
        REJECTED,
        RETRY_LATER
    }

    public enum DeliveryOutcome {
        DELIVERED,
        RETRY,
        UNDELIVERED
    }

    /** 중복 방어를 통과한 최초 요청을 경매 조정자에게 넘기는 메시지다. */
    public record StartAuction(AuctionRequest request) {
    }
}
