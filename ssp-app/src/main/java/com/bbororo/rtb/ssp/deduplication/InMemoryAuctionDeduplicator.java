package com.bbororo.rtb.ssp.deduplication;

import com.bbororo.rtb.ssp.contract.AuctionRequestFingerprint;
import com.bbororo.rtb.ssp.contract.AuctionRequestKey;
import com.bbororo.rtb.ssp.contract.AuctionDeadline;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionResult;
import com.bbororo.rtb.ssp.contract.SspMessages.StartAuction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/** 리전 SSP 인스턴스 안에서만 사용하는 5초 single-flight 중복 방어 구현이다. */
public final class InMemoryAuctionDeduplicator implements AuctionDeduplicator {

    private static final Duration DEFAULT_RETENTION = Duration.ofSeconds(5);
    private static final int DEFAULT_MAXIMUM_ENTRIES = 10_000;

    private final Map<AuctionRequestKey, Flight> flights = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<Expiration> expirations = new PriorityBlockingQueue<>();
    private final Semaphore capacity;
    private final Clock clock;
    private final Duration retention;
    private final int maximumEntries;

    public InMemoryAuctionDeduplicator() {
        this(Clock.systemUTC(), DEFAULT_RETENTION, DEFAULT_MAXIMUM_ENTRIES);
    }

    public InMemoryAuctionDeduplicator(int maximumEntries) {
        this(Clock.systemUTC(), DEFAULT_RETENTION, maximumEntries);
    }

    InMemoryAuctionDeduplicator(Clock clock, Duration retention, int maximumEntries) {
        this.clock = Objects.requireNonNull(clock);
        this.retention = requirePositive(retention, "retention");
        if (maximumEntries <= 0) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.maximumEntries = maximumEntries;
        this.capacity = new Semaphore(maximumEntries);
    }

    @Override
    public CompletionStage<AuctionResult> execute(
            AuctionRequest request,
            AuctionDeadline deadline,
            AuctionStarter starter
    ) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(deadline);
        Objects.requireNonNull(starter);

        Instant now = clock.instant();
        removeExpired(now);

        AuctionRequestKey key = new AuctionRequestKey(request.providerId(), request.providerRequestId());
        AuctionRequestFingerprint fingerprint = request.fingerprint();

        AtomicReference<FlightResolution> resolution = new AtomicReference<>();
        flights.compute(key, (ignored, existing) -> {
            FlightResolution next = existing == null
                    ? createFlight(key, fingerprint)
                    : existing.resolve(fingerprint, now);
            if (next == ExpiredFlight.INSTANCE) {
                next = new StartFlight(new Flight(key, fingerprint));
            }
            resolution.set(next);

            return switch (next) {
                case StartFlight start -> start.flight();
                case ReuseFlight reuse -> reuse.flight();
                case ChangedFlight ignoredChanged -> existing;
                case CapacityExceeded ignoredCapacity -> null;
                case ExpiredFlight ignoredExpired -> throw new IllegalStateException("Expired flight must be replaced");
            };
        });

        return switch (resolution.get()) {
            case StartFlight start -> {
                start(start.flight(), request, deadline, starter);
                yield start.flight().result();
            }
            case ReuseFlight reuse -> reuse.flight().result();
            case ChangedFlight ignored -> CompletableFuture.failedFuture(new ChangedAuctionRequestException(key));
            case CapacityExceeded ignored -> CompletableFuture.failedFuture(
                    new AuctionDeduplicationCapacityException(maximumEntries)
            );
            case ExpiredFlight ignored -> throw new IllegalStateException("Expired flight must be replaced");
        };
    }

    private FlightResolution createFlight(
            AuctionRequestKey key,
            AuctionRequestFingerprint fingerprint
    ) {
        return capacity.tryAcquire()
                ? new StartFlight(new Flight(key, fingerprint))
                : CapacityExceeded.INSTANCE;
    }

    private void start(Flight flight, AuctionRequest request, AuctionDeadline deadline, AuctionStarter starter) {
        try {
            CompletionStage<AuctionResult> started = Objects.requireNonNull(
                    starter.start(new StartAuction(request, deadline)),
                    "AuctionStarter must return a completion stage"
            );
            started.whenComplete(flight::complete);
        } catch (RuntimeException exception) {
            flight.complete(null, exception);
        }
    }

    private void removeExpired(Instant now) {
        while (true) {
            Expiration next = expirations.peek();
            if (next == null || next.expiresAfter(now)) {
                return;
            }
            Expiration expired = expirations.poll();
            if (expired != null && flights.remove(expired.key(), expired.flight())) {
                capacity.release();
            }
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private final class Flight {

        private final AuctionRequestKey key;
        private final AuctionRequestFingerprint fingerprint;
        private final CompletableFuture<AuctionResult> result = new CompletableFuture<>();
        private final AtomicReference<Instant> retainUntil = new AtomicReference<>();

        private Flight(AuctionRequestKey key, AuctionRequestFingerprint fingerprint) {
            this.key = key;
            this.fingerprint = fingerprint;
        }

        private CompletionStage<AuctionResult> result() {
            return result.copy();
        }

        private FlightResolution resolve(AuctionRequestFingerprint candidate, Instant now) {
            if (isExpiredAt(now)) {
                return ExpiredFlight.INSTANCE;
            }
            if (fingerprint.equals(candidate)) {
                return new ReuseFlight(this);
            }
            return ChangedFlight.INSTANCE;
        }

        private void complete(AuctionResult auctionResult, Throwable failure) {
            boolean completed;
            if (failure == null) {
                completed = auctionResult == null
                        ? result.completeExceptionally(
                                new NullPointerException("Auction result must not be null")
                        )
                        : result.complete(auctionResult);
            } else {
                completed = result.completeExceptionally(failure);
            }
            if (completed) {
                Instant expiration = clock.instant().plus(retention);
                retainUntil.set(expiration);
                expirations.add(new Expiration(expiration, key, this));
            }
        }

        private boolean isExpiredAt(Instant now) {
            Instant expiration = retainUntil.get();
            return expiration != null && !now.isBefore(expiration);
        }
    }

    int entryCount() {
        return flights.size();
    }

    private record Expiration(
            Instant expiresAt,
            AuctionRequestKey key,
            Flight flight
    ) implements Comparable<Expiration> {

        private boolean expiresAfter(Instant now) {
            return expiresAt.isAfter(now);
        }

        @Override
        public int compareTo(Expiration other) {
            return expiresAt.compareTo(other.expiresAt);
        }
    }

    private sealed interface FlightResolution permits
            StartFlight,
            ReuseFlight,
            ExpiredFlight,
            ChangedFlight,
            CapacityExceeded {
    }

    private record StartFlight(Flight flight) implements FlightResolution {
    }

    private record ReuseFlight(Flight flight) implements FlightResolution {
    }

    private enum ExpiredFlight implements FlightResolution {
        INSTANCE
    }

    private enum ChangedFlight implements FlightResolution {
        INSTANCE
    }

    private enum CapacityExceeded implements FlightResolution {
        INSTANCE
    }
}
