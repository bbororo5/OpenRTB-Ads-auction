package com.bbororo.rtb.dsp.bidding;

import com.bbororo.rtb.dsp.bidding.BiddingMessages.BidDecision;
import com.bbororo.rtb.dsp.bidding.BiddingMessages.BidExecuted;
import com.bbororo.rtb.dsp.bidding.BiddingMessages.BidExecutionRejected;
import com.bbororo.rtb.dsp.bidding.BiddingMessages.BidExecutionRejection;
import com.bbororo.rtb.dsp.bidding.BiddingMessages.BidExecutionResult;
import com.bbororo.rtb.dsp.bidding.BiddingMessages.BidRequestFingerprint;
import com.bbororo.rtb.dsp.bidding.BiddingMessages.BidRequestKey;
import com.bbororo.rtb.dsp.bidding.BiddingMessages.ExecuteBidOnce;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 한 요청 키의 최초 호출만 입찰 조정을 실행한다. 후속 호출은 최초 결과를 기다리거나 재사용하지 않고
 * 현재 상태와 무관하게 빠르게 거절한다.
 */
public final class DefaultBidExecutionGate implements BidExecutionGate {

    private static final Duration DEFAULT_RETENTION = Duration.ofSeconds(5);
    private static final int DEFAULT_MAX_ENTRIES = 100_000;
    private static final int MAX_EXPIRATIONS_PER_CALL = 64;

    private final long retentionNanos;
    private final int maxEntries;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<BidRequestKey, Entry> entries = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<ExpiryTicket> expirations = new PriorityBlockingQueue<>();
    private final AtomicInteger entryCount = new AtomicInteger();

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
        long now = nanoClock.getAsLong();
        expireTerminalEntries(now);
        while (true) {
            Entry existing = entries.get(key);
            if (existing != null) {
                if (existing.isRetentionExpired(now) && existing.state != EntryState.IN_FLIGHT
                        && removeEntry(key, existing)) {
                    continue;
                }
                return rejectExisting(existing, command.fingerprint());
            }
            if (!tryAcquireCapacity()) {
                return new BidExecutionRejected(BidExecutionRejection.CAPACITY_EXCEEDED);
            }

            Entry claimed = new Entry(command.fingerprint(), now, retentionNanos);
            Entry raced = entries.putIfAbsent(key, claimed);
            if (raced != null) {
                releaseCapacity();
                continue;
            }
            expirations.offer(new ExpiryTicket(key, claimed));

            try {
                BidDecision decision = Objects.requireNonNull(
                        firstExecution.get(), "firstExecution returned null");
                claimed.state = EntryState.TERMINAL;
                removeIfRetentionExpired(key, claimed);
                return new BidExecuted(decision);
            } catch (Throwable failure) {
                claimed.state = EntryState.UNCERTAIN;
                removeIfRetentionExpired(key, claimed);
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

    private void expireTerminalEntries(long now) {
        for (int expired = 0; expired < MAX_EXPIRATIONS_PER_CALL; expired++) {
            ExpiryTicket ticket = expirations.peek();
            if (ticket == null || ticket.entry.retentionUntilNano > now) {
                return;
            }
            expirations.poll();
            if (ticket.entry.state != EntryState.IN_FLIGHT) {
                removeEntry(ticket.key, ticket.entry);
            }
        }
    }

    private boolean tryAcquireCapacity() {
        while (true) {
            int current = entryCount.get();
            if (current >= maxEntries) {
                return false;
            }
            if (entryCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void removeIfRetentionExpired(BidRequestKey key, Entry entry) {
        if (entry.isRetentionExpired(nanoClock.getAsLong())) {
            removeEntry(key, entry);
        }
    }

    private boolean removeEntry(BidRequestKey key, Entry expected) {
        if (!entries.remove(key, expected)) {
            return false;
        }
        releaseCapacity();
        return true;
    }

    private void releaseCapacity() {
        int remaining = entryCount.decrementAndGet();
        if (remaining < 0) {
            throw new IllegalStateException("entry capacity accounting became negative");
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

        private boolean isRetentionExpired(long nowNano) {
            return nowNano - retentionUntilNano >= 0;
        }
    }

    private static final class ExpiryTicket implements Comparable<ExpiryTicket> {
        private final BidRequestKey key;
        private final Entry entry;

        private ExpiryTicket(BidRequestKey key, Entry entry) {
            this.key = key;
            this.entry = entry;
        }

        @Override
        public int compareTo(ExpiryTicket other) {
            return Long.compare(entry.retentionUntilNano, other.entry.retentionUntilNano);
        }
    }
}
