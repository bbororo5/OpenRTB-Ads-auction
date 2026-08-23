package com.bbororo.rtb.dsp.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import com.bbororo.rtb.dsp.auction.AuctionMessages.ExecutionKind;
import com.bbororo.rtb.dsp.contract.AuctionDeadline;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuthenticatedBidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Impression;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("입찰 중복 방지기(DefaultBidDeduplicator) 단위 및 동시성 테스트")
class DefaultBidDeduplicatorTest {

    private static final Duration TTL = Duration.ofSeconds(5);
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    private AtomicLong simulatedNanoClock;
    private DefaultBidDeduplicator deduplicator;

    @BeforeEach
    void setUp() {
        simulatedNanoClock = new AtomicLong(1_000_000_000_000L); // 1000초 기준
        deduplicator = new DefaultBidDeduplicator(TTL, 10_000, simulatedNanoClock::get);
    }

    @Test
    @DisplayName("[T1: 최초 실행] 신규 요청 인입 시 공급자(Supplier)가 1회 실행되고 FIRST로 반환된다")
    void firstExecutionExecutesSupplierAndReturnsFirst() {
        var command = sampleCommand("ssp-1", "req-1", "fp-1");
        var decision = new BidDecision("req-1", List.of());
        var executionCount = new AtomicInteger(0);

        BidExecutionResult result = deduplicator.executeOnce(command, () -> {
            executionCount.incrementAndGet();
            return decision;
        });

        assertEquals(1, executionCount.get());
        var executed = assertInstanceOf(BidExecuted.class, result);
        assertEquals(ExecutionKind.FIRST, executed.kind());
        assertSame(decision, executed.decision());
    }

    @Test
    @DisplayName("[T2: 동시성 싱글플라이트] 2개 스레드가 동일 요청을 동시 인입하면 supplier는 단 1회만 실행되고 결과를 공유한다")
    void concurrentDuplicateRequestsCoalesceToSingleExecution() throws Exception {
        var command = sampleCommand("ssp-1", "req-concurrent", "fp-1");
        var decision = new BidDecision("req-concurrent", List.of());
        var executionCount = new AtomicInteger(0);

        var startLatch = new CountDownLatch(1);
        var supplierBlockLatch = new CountDownLatch(1);
        var supplierProceedLatch = new CountDownLatch(1);

        // 스레드 1: 리더 스레드 (supplier 내부에서 대기)
        CompletableFuture<BidExecutionResult> thread1 = CompletableFuture.supplyAsync(() -> {
            try {
                startLatch.await();
                return deduplicator.executeOnce(command, () -> {
                    executionCount.incrementAndGet();
                    supplierBlockLatch.countDown(); // 리더 진입 신호
                    try {
                        supplierProceedLatch.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return decision;
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        startLatch.countDown();
        assertTrue(supplierBlockLatch.await(2, TimeUnit.SECONDS), "리더가 supplier에 진입해야 함");

        // 스레드 2: 대기자 스레드 (리더가 실행 중인 상태에서 동시 인입)
        CompletableFuture<BidExecutionResult> thread2 = CompletableFuture.supplyAsync(() ->
                deduplicator.executeOnce(command, () -> {
                    executionCount.incrementAndGet(); // 팔로워는 실행되면 안 됨!
                    return decision;
                })
        );

        // 리더 진행 허용
        supplierProceedLatch.countDown();

        BidExecutionResult result1 = thread1.get(2, TimeUnit.SECONDS);
        BidExecutionResult result2 = thread2.get(2, TimeUnit.SECONDS);

        // 검증: supplier는 전체 통틀어 단 1회만 실행되어야 함!
        assertEquals(1, executionCount.get());

        var exec1 = assertInstanceOf(BidExecuted.class, result1);
        var exec2 = assertInstanceOf(BidExecuted.class, result2);

        assertEquals(ExecutionKind.FIRST, exec1.kind());
        assertEquals(ExecutionKind.REUSED, exec2.kind());
        assertSame(decision, exec1.decision());
        assertSame(decision, exec2.decision());
    }

    @Test
    @DisplayName("[T3: 5초 이내 순차 재인입] 2초 뒤 동일 요청 재인입 시 supplier 실행 없이 캐시된 결과가 REUSED로 즉시 반환된다")
    void sequentialDuplicateWithinTtlReturnsReusedWithoutExecutingSupplier() {
        var command = sampleCommand("ssp-1", "req-seq", "fp-1");
        var decision = new BidDecision("req-seq", List.of());
        var executionCount = new AtomicInteger(0);

        // 1차 실행
        deduplicator.executeOnce(command, () -> {
            executionCount.incrementAndGet();
            return decision;
        });

        // 2초 경과 (TTL 5초 이내)
        simulatedNanoClock.addAndGet(TimeUnit.SECONDS.toNanos(2));

        // 2차 실행
        BidExecutionResult result2 = deduplicator.executeOnce(command, () -> {
            executionCount.incrementAndGet(); // 실행 안 되어야 함
            return decision;
        });

        assertEquals(1, executionCount.get());
        var executed2 = assertInstanceOf(BidExecuted.class, result2);
        assertEquals(ExecutionKind.REUSED, executed2.kind());
        assertSame(decision, executed2.decision());
    }

    @Test
    @DisplayName("[T4: 5초 만료 후 재인입] 5초 경과 후 재인입 시 만료 캐시가 삭제되고 신규 요청으로 취급되어 supplier가 재실행된다")
    void duplicateAfterTtlExpiresIsTreatedAsNewExecution() {
        var command = sampleCommand("ssp-1", "req-expire", "fp-1");
        var decision1 = new BidDecision("req-expire", List.of());
        var decision2 = new BidDecision("req-expire", List.of());
        var executionCount = new AtomicInteger(0);

        // 1차 실행 (t = 0s)
        deduplicator.executeOnce(command, () -> {
            executionCount.incrementAndGet();
            return decision1;
        });

        // 5.1초 경과 (TTL 5초 만료)
        simulatedNanoClock.addAndGet(TimeUnit.MILLISECONDS.toNanos(5_100));

        // 2차 실행 (만료 후)
        BidExecutionResult result2 = deduplicator.executeOnce(command, () -> {
            executionCount.incrementAndGet(); // 재실행되어야 함!
            return decision2;
        });

        assertEquals(2, executionCount.get());
        var executed2 = assertInstanceOf(BidExecuted.class, result2);
        assertEquals(ExecutionKind.FIRST, executed2.kind());
        assertSame(decision2, executed2.decision());
        assertNotSame(decision1, executed2.decision());
    }

    @Test
    @DisplayName("[T5: 내용 위변조 충돌] 동일한 요청 키인데 지문(Fingerprint)이 다르면 REQUEST_CONFLICT로 거절된다")
    void differentFingerprintForSameKeyReturnsRequestConflict() {
        var command1 = sampleCommand("ssp-1", "req-tamper", "fp-ORIGINAL");
        var command2 = sampleCommand("ssp-1", "req-tamper", "fp-TAMPERED");
        var decision = new BidDecision("req-tamper", List.of());

        // 1차 등록
        deduplicator.executeOnce(command1, () -> decision);

        // 2차 등록 (동일 키, 다른 지문)
        BidExecutionResult result2 = deduplicator.executeOnce(command2, () -> decision);

        var rejected = assertInstanceOf(BidExecutionRejected.class, result2);
        assertEquals(BidExecutionRejection.REQUEST_CONFLICT, rejected.reason());
    }

    @Test
    @DisplayName("[T6: 실행 실패 정리] supplier 실행 중 예외 발생 시 맵에서 안전하게 제거되어 다음 요청이 정상 재시도된다")
    void failureInSupplierClearsEntryAndAllowsSubsequentRetry() {
        var command = sampleCommand("ssp-1", "req-fail", "fp-1");
        var decision = new BidDecision("req-fail", List.of());

        // 1차 실행: 예외 발생
        assertThrows(IllegalStateException.class, () ->
                deduplicator.executeOnce(command, () -> {
                    throw new IllegalStateException("Budget exhausted or downstream error");
                })
        );

        // 2차 실행: 정상 재시도 가능해야 함
        BidExecutionResult result2 = deduplicator.executeOnce(command, () -> decision);

        var executed2 = assertInstanceOf(BidExecuted.class, result2);
        assertEquals(ExecutionKind.FIRST, executed2.kind());
        assertSame(decision, executed2.decision());
    }

    // =========================================================================
    // 테스트 픽스처 헬퍼
    // =========================================================================
    private static ExecuteBidOnce sampleCommand(String sspId, String requestId, String fingerprint) {
        var key = new BidRequestKey(sspId, requestId);
        String digestHex = "fp-TAMPERED".equals(fingerprint) ? "11".repeat(32) : "00".repeat(32);
        var fp = new BidRequestFingerprint(1, digestHex);
        var openRtbReq = new BidRequest(
                requestId,
                50,
                List.of(new Impression("imp-1", 300, 250, 500, 2))
        );
        var authReq = new AuthenticatedBidRequest(sspId, openRtbReq, NOW);
        var deadline = AuctionDeadline.start(50, System::nanoTime);
        var coordinate = new CoordinateBid(authReq, deadline);
        return new ExecuteBidOnce(key, fp, coordinate);
    }
}
