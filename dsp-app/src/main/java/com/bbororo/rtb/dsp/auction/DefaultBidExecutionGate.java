package com.bbororo.rtb.dsp.auction;

import com.bbororo.rtb.dsp.auction.AuctionMessages.BidDecision;
import com.bbororo.rtb.dsp.auction.AuctionMessages.BidExecuted;
import com.bbororo.rtb.dsp.auction.AuctionMessages.BidExecutionRejected;
import com.bbororo.rtb.dsp.auction.AuctionMessages.BidExecutionRejection;
import com.bbororo.rtb.dsp.auction.AuctionMessages.BidExecutionResult;
import com.bbororo.rtb.dsp.auction.AuctionMessages.BidRequestFingerprint;
import com.bbororo.rtb.dsp.auction.AuctionMessages.BidRequestKey;
import com.bbororo.rtb.dsp.auction.AuctionMessages.ExecuteBidOnce;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 한 요청 키의 최초 호출만 입찰 조정을 실행한다. 후속 호출은 최초 결과를 기다리거나 재사용하지 않고
 * 현재 상태와 무관하게 빠르게 거절한다.
 */
public final class DefaultBidExecutionGate implements BidExecutionGate {

    private static final Duration DEFAULT_RETENTION = Duration.ofSeconds(5);
    private static final int DEFAULT_MAX_ENTRIES = 100_000;

    private final long retentionNanos;
    private final int maxEntries;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<BidRequestKey, Entry> entries = new ConcurrentHashMap<>();

    public DefaultBidExecutionGate() {
        this(DEFAULT_RETENTION, DEFAULT_MAX_ENTRIES, System::nanoTime);
    }

    public DefaultBidExecutionGate(Duration retention, int maxEntries, LongSupplier nanoClock) {
        Objects.requireNonNull(retention, "retention");
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.retentionNanos = retention.toNanos();
        this.maxEntries = maxEntries;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    @Override
    public BidExecutionResult tryExecute(ExecuteBidOnce command, Supplier<BidDecision> firstExecution) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(firstExecution, "firstExecution");

        BidRequestKey key = command.key();
        while (true) {
            Entry existing = entries.get(key);
            if (existing != null) {
                return rejectExisting(existing, command.fingerprint());
            }
            if (entries.size() >= maxEntries) {
                return new BidExecutionRejected(BidExecutionRejection.CAPACITY_EXCEEDED);
            }

            Entry claimed = new Entry(command.fingerprint(), nanoClock.getAsLong(), retentionNanos);
            Entry raced = entries.putIfAbsent(key, claimed);
            if (raced != null) {
                continue;
            }

            try {
                BidDecision decision = Objects.requireNonNull(
                        firstExecution.get(), "firstExecution returned null");
                claimed.state = EntryState.TERMINAL;
                return new BidExecuted(decision);
            } catch (Throwable failure) {
                claimed.state = EntryState.UNCERTAIN;
                if (failure instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                throw new RuntimeException(failure);
            }
        }
    }

    private static BidExecutionResult rejectExisting(
            Entry existing,
            BidRequestFingerprint incomingFingerprint
    ) {
        if (!existing.fingerprint.equals(incomingFingerprint)) {
            return new BidExecutionRejected(BidExecutionRejection.REQUEST_CONFLICT);
        }
        return new BidExecutionRejected(BidExecutionRejection.DUPLICATE_REQUEST);
    }

    private enum EntryState {
        IN_FLIGHT,
        TERMINAL,
        UNCERTAIN
    }

    private static final class Entry {
        private final BidRequestFingerprint fingerprint;
        private final long retentionUntilNano;
        private volatile EntryState state = EntryState.IN_FLIGHT;

        private Entry(BidRequestFingerprint fingerprint, long createdNano, long retentionNanos) {
            this.fingerprint = fingerprint;
            this.retentionUntilNano = Math.addExact(createdNano, retentionNanos);
        }
    }
}
