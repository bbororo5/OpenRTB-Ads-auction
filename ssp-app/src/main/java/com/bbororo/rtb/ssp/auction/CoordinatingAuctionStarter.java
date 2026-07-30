package com.bbororo.rtb.ssp.auction;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionResult;
import com.bbororo.rtb.ssp.contract.SspMessages.StartAuction;
import com.bbororo.rtb.ssp.deduplication.AuctionStarter;
import com.bbororo.rtb.ssp.notification.DspNotificationDelivery;
import com.bbororo.rtb.ssp.renderproof.AuctionResultAssembler;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** 최초 경매 요청을 지정된 실행 자원에서 경매 조정자로 전달한다. */
public final class CoordinatingAuctionStarter implements AuctionStarter {

    private final AuctionCoordinator coordinator;
    private final Executor executor;
    private final AuctionResultAssembler resultAssembler;
    private final DspNotificationDelivery notificationDelivery;

    public CoordinatingAuctionStarter(
            AuctionCoordinator coordinator,
            Executor executor,
            AuctionResultAssembler resultAssembler,
            DspNotificationDelivery notificationDelivery
    ) {
        this.coordinator = Objects.requireNonNull(coordinator);
        this.executor = Objects.requireNonNull(executor);
        this.resultAssembler = Objects.requireNonNull(resultAssembler);
        this.notificationDelivery = Objects.requireNonNull(notificationDelivery);
    }

    @Override
    public CompletionStage<AuctionResult> start(StartAuction auction) {
        return CompletableFuture
                .supplyAsync(() -> coordinator.runAuction(auction), executor)
                .thenApply(outcome -> completeResult(auction, outcome));
    }

    private AuctionResult completeResult(StartAuction auction, AuctionOutcome outcome) {
        notificationDelivery.sendAuctionNotices(outcome.notices());
        return resultAssembler.assemble(auction.request(), outcome);
    }
}
