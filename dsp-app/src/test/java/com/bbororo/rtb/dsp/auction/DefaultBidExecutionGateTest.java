package com.bbororo.rtb.dsp.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.dsp.auction.AuctionMessages.BidDecision;
import com.bbororo.rtb.dsp.auction.AuctionMessages.BidExecuted;
import com.bbororo.rtb.dsp.auction.AuctionMessages.BidExecutionRejected;
import com.bbororo.rtb.dsp.auction.AuctionMessages.BidExecutionRejection;
import com.bbororo.rtb.dsp.auction.AuctionMessages.BidExecutionResult;
import com.bbororo.rtb.dsp.auction.AuctionMessages.BidRequestFingerprint;
import com.bbororo.rtb.dsp.auction.AuctionMessages.BidRequestKey;
import com.bbororo.rtb.dsp.auction.AuctionMessages.CoordinateBid;
import com.bbororo.rtb.dsp.auction.AuctionMessages.ExecuteBidOnce;
import com.bbororo.rtb.dsp.contract.AuctionDeadline;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuthenticatedBidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Impression;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("입찰 실행권 게이트(DefaultBidExecutionGate)")
class DefaultBidExecutionGateTest {

    private static final Duration RETENTION = Duration.ofSeconds(5);
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    private AtomicLong simulatedNanoClock;
    private DefaultBidExecutionGate gate;

    @BeforeEach
    void setUp() {
        simulatedNanoClock = new AtomicLong(1_000_000_000_000L);
        gate = new DefaultBidExecutionGate(RETENTION, 10_000, simulatedNanoClock::get);
    }

    @Test
    @DisplayName("신규 키의 최초 호출만 실행권을 얻는다")
    void firstRequestExecutesOnce() {
        var command = sampleCommand("ssp-1", "request-1", fingerprint(0));
        var decision = new BidDecision("request-1", List.of());
        var executions = new AtomicInteger();

        BidExecutionResult result = gate.tryExecute(command, () -> {
            executions.incrementAndGet();
            return decision;
        });

        assertEquals(1, executions.get());
        assertSame(decision, assertInstanceOf(BidExecuted.class, result).decision());
    }

    @Test
    @DisplayName("진행 중 같은 키·지문은 최초 결과를 기다리지 않고 즉시 거절한다")
    void inFlightDuplicateIsRejectedWithoutWaiting() throws Exception {
        var command = sampleCommand("ssp-1", "request-concurrent", fingerprint(0));
        var decision = new BidDecision("request-concurrent", List.of());
        var leaderEntered = new CountDownLatch(1);
        var releaseLeader = new CountDownLatch(1);
        var executions = new AtomicInteger();

        CompletableFuture<BidExecutionResult> leader = CompletableFuture.supplyAsync(() ->
                gate.tryExecute(command, () -> {
                    executions.incrementAndGet();
                    leaderEntered.countDown();
                    await(releaseLeader);
                    return decision;
                }));

        assertTrue(leaderEntered.await(2, TimeUnit.SECONDS));
        CompletableFuture<BidExecutionResult> duplicate = CompletableFuture.supplyAsync(() ->
                gate.tryExecute(command, () -> {
                    executions.incrementAndGet();
                    return decision;
                }));

        try {
            var rejected = assertInstanceOf(BidExecutionRejected.class,
                    duplicate.get(200, TimeUnit.MILLISECONDS));
            assertEquals(BidExecutionRejection.DUPLICATE_REQUEST, rejected.reason());
            assertEquals(1, executions.get());
        } finally {
            releaseLeader.countDown();
        }
        assertInstanceOf(BidExecuted.class, leader.get(2, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("완료된 같은 키·지문은 결과를 재사용하지 않고 거절한다")
    void completedDuplicateIsRejected() {
        var command = sampleCommand("ssp-1", "request-complete", fingerprint(0));
        var decision = new BidDecision("request-complete", List.of());
        var executions = new AtomicInteger();

        gate.tryExecute(command, () -> {
            executions.incrementAndGet();
            return decision;
        });
        BidExecutionResult duplicate = gate.tryExecute(command, () -> {
            executions.incrementAndGet();
            return decision;
        });

        assertEquals(1, executions.get());
        var rejected = assertInstanceOf(BidExecutionRejected.class, duplicate);
        assertEquals(BidExecutionRejection.DUPLICATE_REQUEST, rejected.reason());
    }

    @Test
    @DisplayName("같은 키·다른 지문은 진행 상태와 무관하게 계약 충돌이다")
    void differentFingerprintIsARequestConflict() {
        var original = sampleCommand("ssp-1", "request-conflict", fingerprint(0));
        var changed = sampleCommand("ssp-1", "request-conflict", fingerprint(1));
        var decision = new BidDecision("request-conflict", List.of());

        gate.tryExecute(original, () -> decision);
        BidExecutionResult conflict = gate.tryExecute(changed, () -> decision);

        var rejected = assertInstanceOf(BidExecutionRejected.class, conflict);
        assertEquals(BidExecutionRejection.REQUEST_CONFLICT, rejected.reason());
    }

    @Test
    @DisplayName("최초 실행 실패도 실행권을 소비하므로 같은 요청을 재실행하지 않는다")
    void failedFirstExecutionStillConsumesTheExecutionRight() {
        var command = sampleCommand("ssp-1", "request-failure", fingerprint(0));
        var executions = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> gate.tryExecute(command, () -> {
            executions.incrementAndGet();
            throw new IllegalStateException("outcome is uncertain");
        }));

        BidExecutionResult duplicate = gate.tryExecute(command, () -> {
            executions.incrementAndGet();
            return new BidDecision("request-failure", List.of());
        });

        assertEquals(1, executions.get());
        var rejected = assertInstanceOf(BidExecutionRejected.class, duplicate);
        assertEquals(BidExecutionRejection.DUPLICATE_REQUEST, rejected.reason());
    }

    @Test
    @DisplayName("SSP namespace가 다르면 같은 requestId도 별도 실행권이다")
    void authenticatedSspNamespacesAreIndependent() {
        var executions = new AtomicInteger();

        gate.tryExecute(sampleCommand("ssp-1", "request-1", fingerprint(0)), () -> {
            executions.incrementAndGet();
            return new BidDecision("request-1", List.of());
        });
        gate.tryExecute(sampleCommand("ssp-2", "request-1", fingerprint(0)), () -> {
            executions.incrementAndGet();
            return new BidDecision("request-1", List.of());
        });

        assertEquals(2, executions.get());
    }

    @Test
    @DisplayName("종결 엔트리는 기억 수명의 정확한 경계에서 제거되어 새 실행권을 허용한다")
    void terminalEntryExpiresAtTheRetentionBoundary() {
        var command = sampleCommand("ssp-1", "request-expiry", fingerprint(0));
        var executions = new AtomicInteger();

        gate.tryExecute(command, () -> decision("request-expiry", executions));
        simulatedNanoClock.addAndGet(RETENTION.toNanos() - 1);
        var beforeBoundary = gate.tryExecute(command, () -> decision("request-expiry", executions));
        assertEquals(BidExecutionRejection.DUPLICATE_REQUEST,
                assertInstanceOf(BidExecutionRejected.class, beforeBoundary).reason());

        simulatedNanoClock.incrementAndGet();
        assertInstanceOf(BidExecuted.class,
                gate.tryExecute(command, () -> decision("request-expiry", executions)));
        assertEquals(2, executions.get());
    }

    @Test
    @DisplayName("진행 중 엔트리는 기억 수명이 지나도 교체하지 않는다")
    void inFlightEntryNeverExpiresIntoASecondExecution() throws Exception {
        var command = sampleCommand("ssp-1", "request-slow", fingerprint(0));
        var leaderEntered = new CountDownLatch(1);
        var releaseLeader = new CountDownLatch(1);
        var executions = new AtomicInteger();

        CompletableFuture<BidExecutionResult> leader = CompletableFuture.supplyAsync(() ->
                gate.tryExecute(command, () -> {
                    executions.incrementAndGet();
                    leaderEntered.countDown();
                    await(releaseLeader);
                    return new BidDecision("request-slow", List.of());
                }));
        assertTrue(leaderEntered.await(2, TimeUnit.SECONDS));
        simulatedNanoClock.addAndGet(RETENTION.toNanos());

        BidExecutionResult duplicate = gate.tryExecute(command, () -> {
            executions.incrementAndGet();
            return new BidDecision("request-slow", List.of());
        });

        assertEquals(BidExecutionRejection.DUPLICATE_REQUEST,
                assertInstanceOf(BidExecutionRejected.class, duplicate).reason());
        assertEquals(1, executions.get());
        releaseLeader.countDown();
        assertInstanceOf(BidExecuted.class, leader.get(2, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("만료 정리는 점유 용량을 회복한다")
    void expiredTerminalEntryReleasesCapacity() {
        gate = new DefaultBidExecutionGate(RETENTION, 1, simulatedNanoClock::get);
        var first = sampleCommand("ssp-1", "request-1", fingerprint(0));
        var second = sampleCommand("ssp-1", "request-2", fingerprint(0));

        gate.tryExecute(first, () -> new BidDecision("request-1", List.of()));
        var full = gate.tryExecute(second, () -> new BidDecision("request-2", List.of()));
        assertEquals(BidExecutionRejection.CAPACITY_EXCEEDED,
                assertInstanceOf(BidExecutionRejected.class, full).reason());

        simulatedNanoClock.addAndGet(RETENTION.toNanos());
        assertInstanceOf(BidExecuted.class,
                gate.tryExecute(second, () -> new BidDecision("request-2", List.of())));
    }

    @Test
    @DisplayName("동시 신규 키 경쟁에서도 실제 실행권 수는 용량 상한을 넘지 않는다")
    void concurrentClaimsRespectTheExactCapacityLimit() throws Exception {
        int capacity = 4;
        int contenders = 32;
        gate = new DefaultBidExecutionGate(RETENTION, capacity, simulatedNanoClock::get);
        var ownersEntered = new CountDownLatch(capacity);
        var releaseOwners = new CountDownLatch(1);
        var executions = new AtomicInteger();

        List<CompletableFuture<BidExecutionResult>> results = java.util.stream.IntStream.range(0, contenders)
                .mapToObj(index -> CompletableFuture.supplyAsync(() -> gate.tryExecute(
                        sampleCommand("ssp-1", "request-" + index, fingerprint(0)),
                        () -> {
                            executions.incrementAndGet();
                            ownersEntered.countDown();
                            await(releaseOwners);
                            return new BidDecision("request-" + index, List.of());
                        })))
                .toList();

        assertTrue(ownersEntered.await(2, TimeUnit.SECONDS));
        releaseOwners.countDown();
        List<BidExecutionResult> completed = results.stream().map(DefaultBidExecutionGateTest::join).toList();

        assertEquals(capacity, executions.get());
        assertEquals(capacity, completed.stream().filter(BidExecuted.class::isInstance).count());
        assertEquals(contenders - capacity, completed.stream()
                .filter(BidExecutionRejected.class::isInstance)
                .map(BidExecutionRejected.class::cast)
                .filter(result -> result.reason() == BidExecutionRejection.CAPACITY_EXCEEDED)
                .count());
    }

    private static ExecuteBidOnce sampleCommand(
            String sspId,
            String requestId,
            BidRequestFingerprint fingerprint
    ) {
        var request = new BidRequest(
                requestId,
                50,
                List.of(new Impression("imp-1", 300, 250, 500, 2))
        );
        var authenticated = new AuthenticatedBidRequest(sspId, request, NOW);
        return new ExecuteBidOnce(
                new BidRequestKey(sspId, requestId),
                fingerprint,
                new CoordinateBid(authenticated, AuctionDeadline.start(50, System::nanoTime))
        );
    }

    private static BidRequestFingerprint fingerprint(int value) {
        return new BidRequestFingerprint(1, "%02x".formatted(value).repeat(32));
    }

    private static BidDecision decision(String requestId, AtomicInteger executions) {
        executions.incrementAndGet();
        return new BidDecision(requestId, List.of());
    }

    private static BidExecutionResult join(CompletableFuture<BidExecutionResult> future) {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            throw new AssertionError(failure.getCause());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", interrupted);
        }
    }
}
