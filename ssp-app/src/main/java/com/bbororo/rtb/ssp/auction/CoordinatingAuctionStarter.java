package com.bbororo.rtb.ssp.auction;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionResult;
import com.bbororo.rtb.ssp.contract.SspMessages.StartAuction;
import com.bbororo.rtb.ssp.deduplication.AuctionStarter;
import com.bbororo.rtb.ssp.notification.DspNotificationDelivery;
import com.bbororo.rtb.ssp.renderproof.AuctionResultAssembler;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
        Objects.requireNonNull(auction);
        var remaining = auction.deadline().remaining();
        if (remaining.isZero()) {
            return CompletableFuture.failedFuture(new AuctionDeadlineExceededException());
        }

        CompletableFuture<AuctionOutcome> coordinated = new CompletableFuture<>();
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                coordinated.complete(coordinator.runAuction(auction));
            } catch (Throwable failure) {
                coordinated.completeExceptionally(failure);
            }
            return null;
        });
        CompletableFuture<AuctionResult> boundedResult = coordinated
                .thenApply(outcome -> completeResult(auction, outcome))
                .copy()
                .orTimeout(remaining.toNanos(), TimeUnit.NANOSECONDS);
        CompletionStage<AuctionResult> normalizedResult = boundedResult
                .exceptionallyCompose(failure -> {
                    Throwable cause = unwrap(failure);
                    if (cause instanceof TimeoutException) {
                        task.cancel(true);
                        return CompletableFuture.failedFuture(
                                new AuctionDeadlineExceededException()
                        );
                    }
                    return CompletableFuture.failedFuture(cause);
                });
        try {
            executor.execute(task);
        } catch (RuntimeException failure) {
            coordinated.completeExceptionally(failure);
        }
        return normalizedResult;
    }

    private AuctionResult completeResult(StartAuction auction, AuctionOutcome outcome) {
        if (auction.deadline().isExpired()) {
            throw new AuctionDeadlineExceededException();
        }
        AuctionResult result = resultAssembler.assemble(auction.request(), outcome);
        if (auction.deadline().isExpired()) {
            throw new AuctionDeadlineExceededException();
        }
        try {
            notificationDelivery.sendAuctionNotices(outcome.notices());
        } catch (RuntimeException ignoredBestEffortNoticeFailure) {
            // nurl·lurl 단발 통지는 공급자 경매 성공을 되돌리지 않는다.
        }
        if (auction.deadline().isExpired()) {
            throw new AuctionDeadlineExceededException();
        }
        return result;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
