package com.bbororo.rtb.ssp.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.ssp.contract.AuctionDeadline;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionNotice;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionWinners;
import com.bbororo.rtb.ssp.contract.SspMessages.ProofIssuance;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderCompleted;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderProof;
import com.bbororo.rtb.ssp.contract.SspMessages.StartAuction;
import com.bbororo.rtb.ssp.contract.SspMessages.VerifiedRender;
import com.bbororo.rtb.ssp.contract.SspMessages.WinningBid;
import com.bbororo.rtb.ssp.notification.DspNotificationDelivery;
import com.bbororo.rtb.ssp.renderproof.AuctionResultAssembler;
import com.bbororo.rtb.ssp.renderproof.RenderProofService;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CoordinatingAuctionStarterTest {

    @Test
    void interruptsAStuckCoordinatorAtTheAbsoluteDeadline() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AuctionCoordinator stuck = ignored -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                interrupted.countDown();
            }
            return emptyOutcome();
        };

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var starter = new CoordinatingAuctionStarter(
                    stuck,
                    executor,
                    assembler(proofService(null)),
                    noOpNotifications()
            );

            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> starter.start(start(AuctionDeadline.start(30, System::nanoTime)))
                            .toCompletableFuture()
                            .join()
            );

            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertInstanceOf(AuctionDeadlineExceededException.class, failure.getCause());
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void failsWhenProofAssemblyConsumesTheRemainingDeadline() {
        AtomicLong nanos = new AtomicLong();
        AuctionDeadline deadline = AuctionDeadline.start(1, nanos::get);
        RenderProofService slowProof = proofService(nanos);
        var starter = new CoordinatingAuctionStarter(
                ignored -> winningOutcome(),
                Runnable::run,
                assembler(slowProof),
                noOpNotifications()
        );

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> starter.start(start(deadline)).toCompletableFuture().join()
        );

        assertInstanceOf(AuctionDeadlineExceededException.class, failure.getCause());
    }

    @Test
    void bestEffortAuctionNoticeFailureDoesNotUndoACompletedAuction() {
        var starter = new CoordinatingAuctionStarter(
                ignored -> emptyOutcome(),
                Runnable::run,
                assembler(proofService(null)),
                new DspNotificationDelivery() {
                    @Override
                    public void sendAuctionNotices(List<AuctionNotice> notices) {
                        throw new IllegalStateException("notice executor unavailable");
                    }

                    @Override
                    public void deliverDueBilling(Instant now) {
                    }
                }
        );

        var result = starter.start(
                start(AuctionDeadline.start(180, System::nanoTime))
        ).toCompletableFuture().join();

        assertEquals("auction-1", result.auctionId());
    }

    private static AuctionResultAssembler assembler(RenderProofService proofService) {
        return new AuctionResultAssembler(
                proofService,
                Clock.fixed(Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC),
                URI.create("https://ssp.test/render")
        );
    }

    private static RenderProofService proofService(AtomicLong nanos) {
        return new RenderProofService() {
            @Override
            public RenderProof issue(ProofIssuance issuance) {
                if (nanos != null) {
                    nanos.addAndGet(Duration.ofMillis(1).toNanos());
                }
                return new RenderProof("proof");
            }

            @Override
            public Optional<VerifiedRender> verify(RenderCompleted completed) {
                return Optional.empty();
            }
        };
    }

    private static DspNotificationDelivery noOpNotifications() {
        return new DspNotificationDelivery() {
            @Override
            public void sendAuctionNotices(List<AuctionNotice> notices) {
            }

            @Override
            public void deliverDueBilling(Instant now) {
            }
        };
    }

    private static StartAuction start(AuctionDeadline deadline) {
        return new StartAuction(
                new AuctionRequest(
                        "provider-1",
                        "key-1",
                        "request-1",
                        180,
                        List.of(new AuctionSlot("imp-1", 300, 250, 0))
                ),
                deadline
        );
    }

    private static AuctionOutcome emptyOutcome() {
        return new AuctionOutcome("auction-1", new AuctionWinners(List.of()), List.of());
    }

    private static AuctionOutcome winningOutcome() {
        URI url = URI.create("https://dsp.test/notice");
        return new AuctionOutcome(
                "auction-1",
                new AuctionWinners(List.of(new WinningBid(
                        "auction-1/imp-1",
                        "imp-1",
                        "dsp-1",
                        "bid-1",
                        2_000,
                        url,
                        url,
                        url
                ))),
                List.of()
        );
    }
}
