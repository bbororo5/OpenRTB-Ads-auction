package com.bbororo.rtb.ssp.auction;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionWinners;
import com.bbororo.rtb.ssp.contract.SspMessages.BidRequestBatch;
import com.bbororo.rtb.ssp.contract.SspMessages.BidResponses;
import com.bbororo.rtb.ssp.contract.SspMessages.StartAuction;
import com.bbororo.rtb.ssp.dspbid.DspBidExecutor;
import com.bbororo.rtb.ssp.winner.WinnerSelector;
import java.util.List;
import java.util.Objects;
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
    public AuctionWinners runAuction(StartAuction command) {
        Objects.requireNonNull(command);
        if (command.deadline().isExpired()) {
            return new AuctionWinners(List.of());
        }

        String auctionId = UUID.randomUUID().toString();
        BidResponses responses = bidExecutor.requestBids(new BidRequestBatch(
                auctionId,
                command.request(),
                dspIds,
                command.deadline()
        ));
        if (command.deadline().isExpired()) {
            return new AuctionWinners(List.of());
        }
        return winnerSelector.selectWinners(auctionId, command.request(), responses);
    }
}
