package com.bbororo.rtb.ssp.auction;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionWinners;
import com.bbororo.rtb.ssp.contract.SspMessages.StartAuction;
import com.bbororo.rtb.ssp.deduplication.AuctionStarter;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** 최초 경매 요청을 지정된 실행 자원에서 경매 조정자로 전달한다. */
public final class CoordinatingAuctionStarter implements AuctionStarter {

    private final AuctionCoordinator coordinator;
    private final Executor executor;

    public CoordinatingAuctionStarter(AuctionCoordinator coordinator, Executor executor) {
        this.coordinator = Objects.requireNonNull(coordinator);
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public CompletionStage<AuctionWinners> start(StartAuction auction) {
        return CompletableFuture.supplyAsync(() -> coordinator.runAuction(auction), executor);
    }
}
