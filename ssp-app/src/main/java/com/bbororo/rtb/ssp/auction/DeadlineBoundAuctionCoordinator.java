package com.bbororo.rtb.ssp.auction;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionNotice;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionWinners;
import com.bbororo.rtb.ssp.contract.SspMessages.BidRequestBatch;
import com.bbororo.rtb.ssp.contract.SspMessages.BidResponses;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcomeKind;
import com.bbororo.rtb.ssp.contract.SspMessages.NoticeKind;
import com.bbororo.rtb.ssp.contract.SspMessages.StartAuction;
import com.bbororo.rtb.ssp.dspbid.DspBidExecutor;
import com.bbororo.rtb.ssp.winner.WinnerSelector;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 절대 기한 안에서 DSP 실행 결과를 낙찰 결정으로 연결한다. */
public final class DeadlineBoundAuctionCoordinator implements AuctionCoordinator {

    private final DspBidExecutor bidExecutor;
    private final WinnerSelector winnerSelector;
    private final List<String> dspIds;

    public DeadlineBoundAuctionCoordinator(
            DspBidExecutor bidExecutor,
            WinnerSelector winnerSelector,
            List<String> dspIds
    ) {
        this.bidExecutor = Objects.requireNonNull(bidExecutor);
        this.winnerSelector = Objects.requireNonNull(winnerSelector);
        this.dspIds = List.copyOf(dspIds);
    }

    @Override
    public AuctionOutcome runAuction(StartAuction command) {
        Objects.requireNonNull(command);
        String auctionId = UUID.randomUUID().toString();
        if (command.deadline().isExpired()) {
            return new AuctionOutcome(auctionId, new AuctionWinners(List.of()), List.of());
        }

        BidResponses responses = bidExecutor.requestBids(new BidRequestBatch(
                auctionId,
                command.request(),
                dspIds,
                command.deadline()
        ));
        if (command.deadline().isExpired()) {
            return new AuctionOutcome(auctionId, new AuctionWinners(List.of()), List.of());
        }
        AuctionWinners winners = winnerSelector.selectWinners(auctionId, command.request(), responses);
        return new AuctionOutcome(auctionId, winners, notices(responses, winners));
    }

    private static List<AuctionNotice> notices(BidResponses responses, AuctionWinners winners) {
        Set<String> winningBids = winners.winners().stream()
                .map(winner -> bidKey(winner.dspId(), winner.bidId(), winner.impId()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return responses.outcomes().stream()
                .filter(outcome -> outcome.kind() == DspCallOutcomeKind.VALID_BID)
                .flatMap(outcome -> outcome.bids().stream())
                .map(bid -> winningBids.contains(bidKey(bid.dspId(), bid.bidId(), bid.impId()))
                        ? new AuctionNotice(NoticeKind.WIN, bid.nurl())
                        : new AuctionNotice(NoticeKind.LOSS, bid.lurl()))
                .toList();
    }

    private static String bidKey(String dspId, String bidId, String impId) {
        return dspId + '\u0000' + bidId + '\u0000' + impId;
    }
}
