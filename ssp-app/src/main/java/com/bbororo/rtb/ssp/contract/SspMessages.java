package com.bbororo.rtb.ssp.contract;

import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
            int tmaxMillis,
            List<AuctionSlot> slots
    ) {
        public AuctionRequest {
            if (tmaxMillis <= 0 || tmaxMillis > 180) {
                throw new IllegalArgumentException("tmaxMillis must be between 1 and 180");
            }
            slots = List.copyOf(slots);
            Set<String> impIds = new HashSet<>();
            for (AuctionSlot slot : slots) {
                if (!impIds.add(slot.impId())) {
                    throw new IllegalArgumentException("Auction slots must not repeat an impId");
                }
            }
        }

        /** 이 요청이 기존 경매와 같은지를 판단하는 SSP 내부 지문이다. */
        public AuctionRequestFingerprint fingerprint() {
            return AuctionRequestFingerprintCalculator.calculate(this);
        }
    }

    public record AuctionSlot(String impId, long floorCpmKrw) {

        public AuctionSlot {
            if (impId == null || impId.isBlank()) {
                throw new IllegalArgumentException("impId must not be blank");
            }
            if (floorCpmKrw < 0) {
                throw new IllegalArgumentException("floorCpmKrw must not be negative");
            }
        }
    }

    public record AuctionResult(String auctionId, List<SlotAuctionResult> slots) {
        public AuctionResult {
            slots = List.copyOf(slots);
        }
    }

    public record SlotAuctionResult(WinningBid winningBid, RenderProof renderProof) {
    }

    /** 마감 안에 끝난 SSP 경매의 식별자와 슬롯별 낙찰 결과다. */
    public record AuctionOutcome(String auctionId, AuctionWinners winners) {
    }

    public record AuctionWinners(List<WinningBid> winners) {
        public AuctionWinners {
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

    public record BidRequestBatch(String auctionId, AuctionRequest auction, List<String> dspIds, AuctionDeadline deadline) {
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

    public record BidResponses(List<DspCallOutcome> outcomes) {
        public BidResponses {
            outcomes = List.copyOf(outcomes);
        }
    }

    public record DspCallOutcome(String dspId, DspCallOutcomeKind kind, List<DspBid> bids) {

        public DspCallOutcome {
            bids = List.copyOf(bids);
            if (kind == DspCallOutcomeKind.VALID_BID && bids.isEmpty()) {
                throw new IllegalArgumentException("VALID_BID must include at least one bid");
            }
            if (kind != DspCallOutcomeKind.VALID_BID && !bids.isEmpty()) {
                throw new IllegalArgumentException(kind + " must not include bids");
            }
        }
    }

    public enum DspCallOutcomeKind {
        VALID_BID,
        NO_BID,
        TIMEOUT,
        INVALID_BID,
        ERROR
    }

    public record EligibleBids(List<DspBid> bids) {
        public EligibleBids {
            bids = List.copyOf(bids);
        }
    }

    public record RenderProof(String encodedValue) {
    }

    public record ProofIssuance(
            AuctionRequest auction,
            String auctionId,
            WinningBid winner,
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
    public record StartAuction(AuctionRequest request, AuctionDeadline deadline) {
    }
}
