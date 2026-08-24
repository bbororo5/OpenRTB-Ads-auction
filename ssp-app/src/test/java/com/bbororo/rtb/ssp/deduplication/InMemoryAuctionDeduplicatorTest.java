package com.bbororo.rtb.ssp.deduplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bbororo.rtb.ssp.contract.AuctionDeadline;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionResult;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InMemoryAuctionDeduplicatorTest {

    private static final AuctionResult RESULT = new AuctionResult(
            "auction-1", List.of(), URI.create("https://ssp.test/render")
    );

    @Test
    void startsOneAuctionAndLetsConcurrentDuplicatesShareItsCompletion() {
        InMemoryAuctionDeduplicator deduplicator = new InMemoryAuctionDeduplicator();
        CompletableFuture<AuctionResult> firstAuction = new CompletableFuture<>();
        AtomicInteger starts = new AtomicInteger();

        CompletionStage<AuctionResult> first = execute(deduplicator, request("request-1", "imp-1"), auction -> {
            starts.incrementAndGet();
            return firstAuction;
        });
        CompletionStage<AuctionResult> duplicate = execute(deduplicator, request("request-1", "imp-1"), auction -> {
            throw new AssertionError("duplicate must not start another auction");
        });

        assertEquals(1, starts.get());
        assertFalse(first == duplicate);

        firstAuction.complete(RESULT);
        assertEquals(RESULT, duplicate.toCompletableFuture().join());
    }

    @Test
    void reusesACompletedResultDuringTheRetentionWindow() {
        InMemoryAuctionDeduplicator deduplicator = new InMemoryAuctionDeduplicator();
        AtomicInteger starts = new AtomicInteger();

        execute(deduplicator, request("request-1", "imp-1"), auction -> {
            starts.incrementAndGet();
            return CompletableFuture.completedFuture(RESULT);
        }).toCompletableFuture().join();
        AuctionResult retry = execute(deduplicator, request("request-1", "imp-1"), auction -> {
            throw new AssertionError("completed result must be reused");
        }).toCompletableFuture().join();

        assertEquals(1, starts.get());
        assertEquals(RESULT, retry);
    }

    @Test
    void rejectsChangedContentForTheSameAuctionRequestKey() {
        InMemoryAuctionDeduplicator deduplicator = new InMemoryAuctionDeduplicator();
        CompletableFuture<AuctionResult> firstAuction = new CompletableFuture<>();
        execute(deduplicator, request("request-1", "imp-1"), ignored -> firstAuction);

        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> execute(deduplicator, request("request-1", "imp-2"), ignored -> firstAuction)
                        .toCompletableFuture()
                        .join()
        );

        assertInstanceOf(ChangedAuctionRequestException.class, exception.getCause());
    }

    @Test
    void startsANewAuctionAfterTheCompletedResultExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-26T00:00:00Z"));
        InMemoryAuctionDeduplicator deduplicator = new InMemoryAuctionDeduplicator(
                clock, Duration.ofSeconds(5), 10);
        AtomicInteger starts = new AtomicInteger();

        execute(deduplicator, request("request-1", "imp-1"), auction -> {
            starts.incrementAndGet();
            return CompletableFuture.completedFuture(RESULT);
        }).toCompletableFuture().join();
        clock.advance(Duration.ofSeconds(5));

        execute(deduplicator, request("request-1", "imp-1"), auction -> {
            starts.incrementAndGet();
            return CompletableFuture.completedFuture(RESULT);
        }).toCompletableFuture().join();

        assertEquals(2, starts.get());
    }

    @Test
    void callerCancellationDoesNotCancelTheSharedAuction() {
        InMemoryAuctionDeduplicator deduplicator = new InMemoryAuctionDeduplicator();
        CompletableFuture<AuctionResult> sharedAuction = new CompletableFuture<>();

        CompletionStage<AuctionResult> first = execute(
                deduplicator,
                request("request-1", "imp-1"),
                ignored -> sharedAuction
        );
        CompletionStage<AuctionResult> duplicate = execute(
                deduplicator,
                request("request-1", "imp-1"),
                ignored -> {
                    throw new AssertionError("duplicate must not start another auction");
                }
        );

        first.toCompletableFuture().cancel(false);
        sharedAuction.complete(RESULT);

        assertEquals(RESULT, duplicate.toCompletableFuture().join());
    }

    @Test
    void reusesTheFirstTechnicalFailureInsteadOfStartingAnotherAuction() {
        InMemoryAuctionDeduplicator deduplicator = new InMemoryAuctionDeduplicator();
        AtomicInteger starts = new AtomicInteger();
        AuctionStarter failingStarter = ignored -> {
            starts.incrementAndGet();
            throw new IllegalStateException("auction failed");
        };

        CompletionException first = assertThrows(
                CompletionException.class,
                () -> execute(
                        deduplicator,
                        request("request-1", "imp-1"),
                        failingStarter
                ).toCompletableFuture().join()
        );
        CompletionException duplicate = assertThrows(
                CompletionException.class,
                () -> execute(
                        deduplicator,
                        request("request-1", "imp-1"),
                        failingStarter
                ).toCompletableFuture().join()
        );

        assertEquals(1, starts.get());
        assertEquals("auction failed", first.getCause().getMessage());
        assertEquals("auction failed", duplicate.getCause().getMessage());
    }

    @Test
    void rejectsOnlyNewKeysWhenCapacityIsExhausted() {
        InMemoryAuctionDeduplicator deduplicator = new InMemoryAuctionDeduplicator(
                Clock.systemUTC(),
                Duration.ofSeconds(5),
                1
        );
        CompletableFuture<AuctionResult> firstAuction = new CompletableFuture<>();
        execute(deduplicator, request("request-1", "imp-1"), ignored -> firstAuction);

        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> execute(
                        deduplicator,
                        request("request-2", "imp-1"),
                        ignored -> CompletableFuture.completedFuture(RESULT)
                ).toCompletableFuture().join()
        );
        CompletionStage<AuctionResult> duplicate = execute(
                deduplicator,
                request("request-1", "imp-1"),
                ignored -> {
                    throw new AssertionError("known key must still be reusable");
                }
        );

        assertInstanceOf(AuctionDeduplicationCapacityException.class, exception.getCause());
        firstAuction.complete(RESULT);
        assertEquals(RESULT, duplicate.toCompletableFuture().join());
        assertEquals(1, deduplicator.entryCount());
    }

    @Test
    void expiredEntriesReleaseCapacityWithoutScanningLiveEntries() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-26T00:00:00Z"));
        InMemoryAuctionDeduplicator deduplicator = new InMemoryAuctionDeduplicator(
                clock,
                Duration.ofSeconds(5),
                1
        );
        execute(
                deduplicator,
                request("request-1", "imp-1"),
                ignored -> CompletableFuture.completedFuture(RESULT)
        ).toCompletableFuture().join();
        clock.advance(Duration.ofSeconds(5));

        execute(
                deduplicator,
                request("request-2", "imp-1"),
                ignored -> CompletableFuture.completedFuture(RESULT)
        ).toCompletableFuture().join();

        assertEquals(1, deduplicator.entryCount());
    }

    @Test
    void concurrentUniqueRequestsNeverExceedTheCapacity() throws Exception {
        InMemoryAuctionDeduplicator deduplicator = new InMemoryAuctionDeduplicator(
                Clock.systemUTC(),
                Duration.ofSeconds(5),
                4
        );
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger auctionsStarted = new AtomicInteger();
        List<Future<CompletionStage<AuctionResult>>> attempts = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < 32; index++) {
                int requestNumber = index;
                attempts.add(executor.submit(() -> {
                    start.await();
                    return execute(
                            deduplicator,
                            request("request-" + requestNumber, "imp-1"),
                            ignored -> {
                                auctionsStarted.incrementAndGet();
                                return new CompletableFuture<>();
                            }
                    );
                }));
            }
            start.countDown();
            for (Future<CompletionStage<AuctionResult>> attempt : attempts) {
                attempt.get();
            }
        }

        assertEquals(4, auctionsStarted.get());
        assertEquals(4, deduplicator.entryCount());
    }

    private static AuctionRequest request(String providerRequestId, String impId) {
        return new AuctionRequest(
                "provider-1",
                "key-1",
                providerRequestId,
                180,
                List.of(new AuctionSlot(impId, 300, 250, 0))
        );
    }

    private static CompletionStage<AuctionResult> execute(
            InMemoryAuctionDeduplicator deduplicator,
            AuctionRequest request,
            AuctionStarter starter
    ) {
        return deduplicator.execute(request, AuctionDeadline.start(request.tmaxMillis(), System::nanoTime), starter);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
