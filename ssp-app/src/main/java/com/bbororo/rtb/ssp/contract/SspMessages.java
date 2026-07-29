package com.bbororo.rtb.ssp.contract;

import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

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
            providerId = requireNonBlank(providerId, "providerId");
            providerKeyId = requireNonBlank(providerKeyId, "providerKeyId");
            providerRequestId = requireNonBlank(providerRequestId, "providerRequestId");
            if (tmaxMillis <= 0 || tmaxMillis > 180) {
                throw new IllegalArgumentException("tmaxMillis must be between 1 and 180");
            }
            slots = immutableList(slots, "slots");
            if (slots.isEmpty()) {
                throw new IllegalArgumentException("slots must not be empty");
            }
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

    public record AuctionSlot(String impId, long floorCpmMilliKrw) {

        public AuctionSlot {
            if (impId == null || impId.isBlank()) {
                throw new IllegalArgumentException("impId must not be blank");
            }
            if (floorCpmMilliKrw < 0) {
                throw new IllegalArgumentException("floorCpmMilliKrw must not be negative");
            }
        }
    }

    public record AuctionResult(String auctionId, List<SlotAuctionResult> slots) {
        public AuctionResult {
            auctionId = requireNonBlank(auctionId, "auctionId");
            slots = immutableList(slots, "slots");
        }
    }

    public record SlotAuctionResult(WinningBid winningBid, RenderProof renderProof) {
        public SlotAuctionResult {
            Objects.requireNonNull(winningBid, "winningBid");
            Objects.requireNonNull(renderProof, "renderProof");
        }
    }

    /** 마감 안에 끝난 SSP 경매의 식별자와 슬롯별 낙찰 결과다. */
    public record AuctionOutcome(String auctionId, AuctionWinners winners, List<AuctionNotice> notices) {
        public AuctionOutcome {
            auctionId = requireNonBlank(auctionId, "auctionId");
            Objects.requireNonNull(winners, "winners");
            notices = immutableList(notices, "notices");
        }
    }

    public record AuctionWinners(List<WinningBid> winners) {
        public AuctionWinners {
            winners = immutableList(winners, "winners");
            requireUnique(winners, WinningBid::impId, "winners must not repeat an impId");
        }
    }

    public record WinningBid(
            String slotAuctionKey,
            String impId,
            String dspId,
            String bidId,
            long cpmMilliKrw,
            URI nurl,
            URI lurl,
            URI burl
    ) {
        public WinningBid {
            slotAuctionKey = requireNonBlank(slotAuctionKey, "slotAuctionKey");
            impId = requireNonBlank(impId, "impId");
            dspId = requireNonBlank(dspId, "dspId");
            bidId = requireNonBlank(bidId, "bidId");
            requirePositive(cpmMilliKrw, "cpmMilliKrw");
            nurl = requireHttpUrl(nurl, "nurl");
            lurl = requireHttpUrl(lurl, "lurl");
            burl = requireHttpUrl(burl, "burl");
        }
    }

    public record BidRequestBatch(String auctionId, AuctionRequest auction, List<String> dspIds, AuctionDeadline deadline) {
        public BidRequestBatch {
            auctionId = requireNonBlank(auctionId, "auctionId");
            Objects.requireNonNull(auction, "auction");
            dspIds = immutableList(dspIds, "dspIds").stream()
                    .map(dspId -> requireNonBlank(dspId, "dspId"))
                    .toList();
            if (dspIds.isEmpty()) {
                throw new IllegalArgumentException("dspIds must not be empty");
            }
            requireUnique(dspIds, Function.identity(), "dspIds must not contain duplicates");
            Objects.requireNonNull(deadline, "deadline");
        }
    }

    public record DspBid(
            String dspId,
            String impId,
            String bidId,
            long cpmMilliKrw,
            URI nurl,
            URI lurl,
            URI burl
    ) {
        public DspBid {
            dspId = requireNonBlank(dspId, "dspId");
            impId = requireNonBlank(impId, "impId");
            bidId = requireNonBlank(bidId, "bidId");
            requirePositive(cpmMilliKrw, "cpmMilliKrw");
            nurl = requireHttpUrl(nurl, "nurl");
            lurl = requireHttpUrl(lurl, "lurl");
            burl = requireHttpUrl(burl, "burl");
        }
    }

    public record BidResponses(List<DspCallOutcome> outcomes) {
        public BidResponses {
            outcomes = immutableList(outcomes, "outcomes");
            requireUnique(outcomes, DspCallOutcome::dspId, "outcomes must not repeat a dspId");
        }
    }

    public record DspCallOutcome(String dspId, DspCallOutcomeKind kind, List<DspBid> bids) {

        public DspCallOutcome {
            dspId = requireNonBlank(dspId, "dspId");
            Objects.requireNonNull(kind, "kind");
            bids = immutableList(bids, "bids");
            if (kind == DspCallOutcomeKind.VALID_BID && bids.isEmpty()) {
                throw new IllegalArgumentException("VALID_BID must include at least one bid");
            }
            if (kind != DspCallOutcomeKind.VALID_BID && !bids.isEmpty()) {
                throw new IllegalArgumentException(kind + " must not include bids");
            }
            for (DspBid bid : bids) {
                if (!dspId.equals(bid.dspId())) {
                    throw new IllegalArgumentException("Every bid must belong to the outcome DSP");
                }
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

    public record RenderProof(String encodedValue) {
        public RenderProof {
            encodedValue = requireNonBlank(encodedValue, "encodedValue");
        }
    }

    public record ProofIssuance(
            AuctionRequest auction,
            String auctionId,
            WinningBid winner,
            Instant issuedAt,
            Instant expiresAt
    ) {
        public ProofIssuance {
            Objects.requireNonNull(auction, "auction");
            auctionId = requireNonBlank(auctionId, "auctionId");
            Objects.requireNonNull(winner, "winner");
            requireOrderedInstants(issuedAt, expiresAt, "expiresAt must be after issuedAt");
        }
    }

    public record RenderCompleted(RenderProof renderProof, Instant receivedAt) {
        public RenderCompleted {
            Objects.requireNonNull(renderProof, "renderProof");
            Objects.requireNonNull(receivedAt, "receivedAt");
        }
    }

    public record VerifiedRender(
            String providerId,
            String providerRequestId,
            String impId,
            String slotAuctionKey,
            String proofDigest,
            String dspId,
            long cpmMilliKrw,
            URI billingUrl,
            Instant auctionIssuedAt,
            Instant renderExpiresAt
    ) {
        public VerifiedRender {
            providerId = requireNonBlank(providerId, "providerId");
            providerRequestId = requireNonBlank(providerRequestId, "providerRequestId");
            impId = requireNonBlank(impId, "impId");
            slotAuctionKey = requireNonBlank(slotAuctionKey, "slotAuctionKey");
            proofDigest = requireSha256Hex(proofDigest);
            dspId = requireNonBlank(dspId, "dspId");
            requirePositive(cpmMilliKrw, "cpmMilliKrw");
            billingUrl = requireHttpUrl(billingUrl, "billingUrl");
            requireOrderedInstants(
                    auctionIssuedAt,
                    renderExpiresAt,
                    "renderExpiresAt must be after auctionIssuedAt"
            );
        }
    }

    public record BillingClaim(
            String providerId,
            String providerRequestId,
            String impId,
            String slotAuctionKey,
            String proofDigest,
            String dspId,
            long cpmMilliKrw,
            URI billingUrl,
            Instant billingDeadline
    ) {
        public BillingClaim {
            providerId = requireNonBlank(providerId, "providerId");
            providerRequestId = requireNonBlank(providerRequestId, "providerRequestId");
            impId = requireNonBlank(impId, "impId");
            slotAuctionKey = requireNonBlank(slotAuctionKey, "slotAuctionKey");
            proofDigest = requireSha256Hex(proofDigest);
            dspId = requireNonBlank(dspId, "dspId");
            requirePositive(cpmMilliKrw, "cpmMilliKrw");
            billingUrl = requireHttpUrl(billingUrl, "billingUrl");
            Objects.requireNonNull(billingDeadline, "billingDeadline");
        }
    }

    public record BillingDeliveryTask(String deliveryId, BillingClaim claim) {
        public BillingDeliveryTask {
            deliveryId = requireNonBlank(deliveryId, "deliveryId");
            Objects.requireNonNull(claim, "claim");
        }
    }

    public record DeliveryLease(String deliveryId, long generation, Instant leaseUntil) {
        public DeliveryLease {
            deliveryId = requireNonBlank(deliveryId, "deliveryId");
            requirePositive(generation, "generation");
            Objects.requireNonNull(leaseUntil, "leaseUntil");
        }
    }

    public record LeasedBillingDelivery(BillingDeliveryTask task, DeliveryLease lease) {
        public LeasedBillingDelivery {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(lease, "lease");
            if (!task.deliveryId().equals(lease.deliveryId())) {
                throw new IllegalArgumentException("task and lease must have the same deliveryId");
            }
        }
    }

    public record AuctionNotice(NoticeKind kind, URI url) {
        public AuctionNotice {
            Objects.requireNonNull(kind, "kind");
            url = requireHttpUrl(url, "url");
        }
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
        public StartAuction {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(deadline, "deadline");
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static URI requireHttpUrl(URI value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isAbsolute()
                || (!"http".equalsIgnoreCase(value.getScheme())
                && !"https".equalsIgnoreCase(value.getScheme()))) {
            throw new IllegalArgumentException(name + " must be an absolute HTTP URL");
        }
        return value;
    }

    private static String requireSha256Hex(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("proofDigest must be a lowercase SHA-256 hex value");
        }
        return value;
    }

    private static void requireOrderedInstants(Instant first, Instant second, String message) {
        Objects.requireNonNull(first, "first instant");
        Objects.requireNonNull(second, "second instant");
        if (!second.isAfter(first)) {
            throw new IllegalArgumentException(message);
        }
    }

    private static <T> List<T> immutableList(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        return List.copyOf(values);
    }

    private static <T, K> void requireUnique(List<T> values, Function<T, K> key, String message) {
        Set<K> keys = new HashSet<>();
        for (T value : values) {
            if (!keys.add(key.apply(value))) {
                throw new IllegalArgumentException(message);
            }
        }
    }
}
