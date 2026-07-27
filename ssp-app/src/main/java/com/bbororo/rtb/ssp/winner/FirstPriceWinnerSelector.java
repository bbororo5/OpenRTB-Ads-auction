package com.bbororo.rtb.ssp.winner;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionWinners;
import com.bbororo.rtb.ssp.contract.SspMessages.BidResponses;
import com.bbororo.rtb.ssp.contract.SspMessages.DspBid;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcomeKind;
import com.bbororo.rtb.ssp.contract.SspMessages.WinningBid;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 유효 입찰 중 슬롯별 최고 CPM을 1가격으로 선택한다. */
public final class FirstPriceWinnerSelector implements WinnerSelector {

    private static final Comparator<DspBid> WINNER_ORDER = Comparator
            .comparingLong(DspBid::cpmKrw).reversed()
            .thenComparing(DspBid::dspId)
            .thenComparing(DspBid::bidId);

    @Override
    public AuctionWinners selectWinners(String auctionId, AuctionRequest auction, BidResponses responses) {
        Objects.requireNonNull(auctionId);
        Objects.requireNonNull(auction);
        Objects.requireNonNull(responses);

        Map<String, AuctionSlot> slotsByImpId = auction.slots().stream()
                .collect(Collectors.toUnmodifiableMap(AuctionSlot::impId, Function.identity()));

        Map<String, DspBid> winnersByImpId = responses.outcomes().stream()
                .filter(outcome -> outcome.kind() == DspCallOutcomeKind.VALID_BID)
                .flatMap(outcome -> outcome.bids().stream().filter(bid -> isValid(bid, outcome, slotsByImpId)))
                .collect(Collectors.toMap(
                        DspBid::impId,
                        Function.identity(),
                        FirstPriceWinnerSelector::betterBid
                ));

        List<WinningBid> winners = auction.slots().stream()
                .map(AuctionSlot::impId)
                .map(impId -> toWinningBid(auctionId, winnersByImpId.get(impId)))
                .filter(Objects::nonNull)
                .toList();
        return new AuctionWinners(winners);
    }

    private static boolean isValid(DspBid bid, DspCallOutcome outcome, Map<String, AuctionSlot> slotsByImpId) {
        AuctionSlot slot = slotsByImpId.get(bid.impId());
        return bid.dspId().equals(outcome.dspId())
                && slot != null
                && bid.cpmKrw() > 0
                && bid.cpmKrw() >= slot.floorCpmKrw();
    }

    private static DspBid betterBid(DspBid first, DspBid second) {
        return WINNER_ORDER.compare(first, second) <= 0 ? first : second;
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
                bid.cpmKrw(),
                bid.nurl(),
                bid.lurl(),
                bid.burl()
        );
    }
}
