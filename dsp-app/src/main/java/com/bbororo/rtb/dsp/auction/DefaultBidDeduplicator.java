package com.bbororo.rtb.dsp.auction;

import com.bbororo.rtb.dsp.auction.AuctionMessages.BidDecision;
import com.bbororo.rtb.dsp.auction.AuctionMessages.BidExecuted;
import com.bbororo.rtb.dsp.auction.AuctionMessages.BidExecutionRejected;
import com.bbororo.rtb.dsp.auction.AuctionMessages.BidExecutionRejection;
import com.bbororo.rtb.dsp.auction.AuctionMessages.BidExecutionResult;
import com.bbororo.rtb.dsp.auction.AuctionMessages.BidRequestFingerprint;
import com.bbororo.rtb.dsp.auction.AuctionMessages.BidRequestKey;
import com.bbororo.rtb.dsp.auction.AuctionMessages.ExecuteBidOnce;
import com.bbororo.rtb.dsp.auction.AuctionMessages.ExecutionKind;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 5초 TTL 윈도우 내에서 동일한 입찰 요청 키(sspId + requestId)의 동시성 중복 실행을 병합(Singleflight)하고,
 * 내용 위변조(Fingerprint Conflict)를 감지하며 완성된 결과를 락 없이 재사용(REUSED)한다.
 */
public final class DefaultBidDeduplicator implements BidDeduplicator {

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(5);
    private static final int DEFAULT_MAX_ENTRIES = 100_000;

    private final long ttlNanos;
    private final int maxEntries;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<BidRequestKey, Entry> entries = new ConcurrentHashMap<>();

    public DefaultBidDeduplicator() {
        this(DEFAULT_TTL, DEFAULT_MAX_ENTRIES, System::nanoTime);
    }

    public DefaultBidDeduplicator(Duration ttl, int maxEntries, LongSupplier nanoClock) {
        Objects.requireNonNull(ttl, "ttl");
        this.ttlNanos = ttl.toNanos();
        this.maxEntries = maxEntries;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    @Override
    public BidExecutionResult executeOnce(ExecuteBidOnce command, Supplier<BidDecision> firstExecution) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(firstExecution, "firstExecution");

        BidRequestKey key = command.key();
        BidRequestFingerprint fp = command.fingerprint();
        long now = nanoClock.getAsLong();

        while (true) {
            Entry existing = entries.get(key);
            if (existing != null) {
                // 1. 5초 TTL 만료 검사
                if (existing.isExpired(now, ttlNanos)) {
                    entries.remove(key, existing);
                    continue; // 만료된 캐시 제거 후 신규 요청으로 재시도
                }

                // 2. 내용 위변조 검사 (동일 키, 다른 지문)
                if (!existing.fingerprint.equals(fp)) {
                    return new BidExecutionRejected(BidExecutionRejection.REQUEST_CONFLICT);
                }

                // 3. 지문 일치: 리더 스레드의 결과를 안전하게 대기/공유 (Singleflight / 5s Cache)
                try {
                    BidDecision decision = existing.future.join();
                    return new BidExecuted(decision, ExecutionKind.REUSED);
                } catch (CompletionException ce) {
                    Throwable cause = ce.getCause();
                    if (cause instanceof RuntimeException re) {
                        throw re;
                    }
                    if (cause instanceof Error e) {
                        throw e;
                    }
                    throw new RuntimeException(cause);
                }
            }

            // 4. 최대 용량 상한 검사
            if (entries.size() >= maxEntries) {
                return new BidExecutionRejected(BidExecutionRejection.CAPACITY_EXCEEDED);
            }

            // 5. 리더 스레드 선점 시도 (CAS putIfAbsent)
            Entry newEntry = new Entry(fp, now);
            Entry previous = entries.putIfAbsent(key, newEntry);
            if (previous != null) {
                continue; // 경쟁에서 밀림 -> 다음 루프에서 기존 엔트리를 팔로우
            }

            // 6. 리더 스레드: 실제 입찰 연산 단 1회 실행
            try {
                BidDecision decision = firstExecution.get();
                Objects.requireNonNull(decision, "firstExecution returned null");
                newEntry.decision = decision;
                newEntry.future.complete(decision);
                return new BidExecuted(decision, ExecutionKind.FIRST);
            } catch (Throwable failure) {
                entries.remove(key, newEntry);
                newEntry.future.completeExceptionally(failure);
                if (failure instanceof RuntimeException re) {
                    throw re;
                }
                if (failure instanceof Error e) {
                    throw e;
                }
                throw new RuntimeException(failure);
            }
        }
    }

    private static final class Entry {
        final BidRequestFingerprint fingerprint;
        final long createdNano;
        final CompletableFuture<BidDecision> future;
        volatile BidDecision decision;

        Entry(BidRequestFingerprint fingerprint, long createdNano) {
            this.fingerprint = fingerprint;
            this.createdNano = createdNano;
            this.future = new CompletableFuture<>();
        }

        boolean isExpired(long nowNano, long ttlNanos) {
            return (nowNano - createdNano) >= ttlNanos;
        }
    }
}
