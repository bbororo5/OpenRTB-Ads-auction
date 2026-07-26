package com.bbororo.rtb.ssp.deduplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bbororo.rtb.ssp.contract.AuctionDeadline;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionResult;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderProof;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InMemoryAuctionDeduplicatorTest {

    private static final AuctionResult RESULT = new AuctionResult("auction-1", List.of(), new RenderProof("proof"));

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
        assertSame(first, duplicate);

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
                clock, Duration.ofSeconds(5), Duration.ofSeconds(1));
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

    private static AuctionRequest request(String providerRequestId, String impId) {
        return new AuctionRequest(
                "provider-1",
                "key-1",
                providerRequestId,
                180,
                List.of(new AuctionSlot(impId, 0))
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
