package com.bbororo.rtb.ssp.winner;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionWinners;
import com.bbororo.rtb.ssp.contract.SspMessages.BidResponses;
import com.bbororo.rtb.ssp.contract.SspMessages.DspBid;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcomeKind;
import com.bbororo.rtb.ssp.contract.SspMessages.WinningBid;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 유효 입찰 중 슬롯별 최고 CPM을 1가격으로 선택한다. */
public final class FirstPriceWinnerSelector implements WinnerSelector {

    @Override
    public AuctionWinners selectWinners(String auctionId, AuctionRequest auction, BidResponses responses) {
        if (auctionId == null || auctionId.isBlank()) {
            throw new IllegalArgumentException("auctionId must not be blank");
        }
        Objects.requireNonNull(auction);
        Objects.requireNonNull(responses);

        Map<String, AuctionSlot> slotsByImpId = auction.slots().stream()
                .collect(Collectors.toUnmodifiableMap(AuctionSlot::impId, Function.identity()));

        Map<String, DspBid> winnersByImpId = responses.outcomes().stream()
                .filter(outcome -> outcome.kind() == DspCallOutcomeKind.VALID_BID)
                .flatMap(outcome -> outcome.bids().stream())
                .filter(bid -> isEligible(bid, slotsByImpId))
                .collect(Collectors.toMap(
                        DspBid::impId,
                        Function.identity(),
                        (first, second) -> betterBid(auctionId, first, second)
                ));

        List<WinningBid> winners = auction.slots().stream()
                .map(AuctionSlot::impId)
                .map(impId -> toWinningBid(auctionId, winnersByImpId.get(impId)))
                .filter(Objects::nonNull)
                .toList();
        return new AuctionWinners(winners);
    }

    private static boolean isEligible(DspBid bid, Map<String, AuctionSlot> slotsByImpId) {
        AuctionSlot slot = slotsByImpId.get(bid.impId());
        return slot != null
                && bid.cpmMilliKrw() >= slot.floorCpmMilliKrw();
    }

    private static DspBid betterBid(String auctionId, DspBid first, DspBid second) {
        int priceOrder = Long.compare(first.cpmMilliKrw(), second.cpmMilliKrw());
        if (priceOrder != 0) {
            return priceOrder > 0 ? first : second;
        }
        int rankOrder = Long.compareUnsigned(tieRank(auctionId, first), tieRank(auctionId, second));
        if (rankOrder != 0) {
            return rankOrder < 0 ? first : second;
        }
        int dspOrder = first.dspId().compareTo(second.dspId());
        if (dspOrder != 0) {
            return dspOrder < 0 ? first : second;
        }
        return first.bidId().compareTo(second.bidId()) <= 0 ? first : second;
    }

    private static WinningBid toWinningBid(String auctionId, DspBid bid) {
        if (bid == null) {
            return null;
        }
        return new WinningBid(
                auctionId + "/" + bid.impId(),
                bid.impId(),
                bid.dspId(),
                bid.bidId(),
                bid.cpmMilliKrw(),
                bid.nurl(),
                bid.lurl(),
                bid.burl()
        );
    }

    /**
     * 동가 입찰을 응답 도착 순서나 고정 DSP 우선순위에 의존하지 않고 분산하는 순위다.
     *
     * <p>보안용 해시가 아니므로 할당 없이 계산 가능한 64비트 FNV-1a를 사용한다.</p>
     */
    private static long tieRank(String auctionId, DspBid bid) {
        long hash = 0xcbf29ce484222325L;
        hash = hash(hash, auctionId);
        hash = hash(hash, bid.impId());
        hash = hash(hash, bid.dspId());
        return hash(hash, bid.bidId());
    }

    private static long hash(long initial, String value) {
        long hash = initial ^ value.length();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            hash ^= character & 0xff;
            hash *= 0x100000001b3L;
            hash ^= character >>> 8;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

}
