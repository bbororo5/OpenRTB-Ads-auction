package com.bbororo.rtb.ssp.deduplication;

import com.bbororo.rtb.ssp.contract.AuctionRequestFingerprint;
import com.bbororo.rtb.ssp.contract.AuctionRequestKey;
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
import java.util.concurrent.atomic.AtomicReference;

/** 리전 SSP 인스턴스 안에서만 사용하는 5초 single-flight 중복 방어 구현이다. */
public final class InMemoryAuctionDeduplicator implements AuctionDeduplicator {

    private static final Duration DEFAULT_RETENTION = Duration.ofSeconds(5);
    private static final Duration DEFAULT_CLEANUP_INTERVAL = Duration.ofSeconds(1);

    private final Map<AuctionRequestKey, Flight> flights = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration retention;
    private final Duration cleanupInterval;
    private final AtomicReference<Instant> lastCleanupAt = new AtomicReference<>();

    public InMemoryAuctionDeduplicator() {
        this(Clock.systemUTC(), DEFAULT_RETENTION, DEFAULT_CLEANUP_INTERVAL);
    }

    InMemoryAuctionDeduplicator(Clock clock, Duration retention, Duration cleanupInterval) {
        this.clock = Objects.requireNonNull(clock);
        this.retention = requirePositive(retention, "retention");
        this.cleanupInterval = requirePositive(cleanupInterval, "cleanupInterval");
    }

    @Override
    public CompletionStage<AuctionResult> execute(AuctionRequest request, AuctionStarter starter) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(starter);

        Instant now = clock.instant();
        cleanupIfDue(now);

        AuctionRequestKey key = new AuctionRequestKey(request.providerId(), request.providerRequestId());
        AuctionRequestFingerprint fingerprint = request.fingerprint();

        AtomicReference<FlightResolution> resolution = new AtomicReference<>();
        flights.compute(key, (ignored, existing) -> {
            FlightResolution next = existing == null
                    ? new StartFlight(new Flight(fingerprint))
                    : existing.resolve(fingerprint, now);
            if (next == ExpiredFlight.INSTANCE) {
                next = new StartFlight(new Flight(fingerprint));
            }
            resolution.set(next);

            return switch (next) {
                case StartFlight start -> start.flight();
                case ReuseFlight reuse -> reuse.flight();
                case ChangedFlight ignoredChanged -> existing;
                case ExpiredFlight ignoredExpired -> throw new IllegalStateException("Expired flight must be replaced");
            };
        });

        return switch (resolution.get()) {
            case StartFlight start -> {
                start(start.flight(), request, starter);
                yield start.flight().result();
            }
            case ReuseFlight reuse -> reuse.flight().result();
            case ChangedFlight ignored -> CompletableFuture.failedFuture(new ChangedAuctionRequestException(key));
            case ExpiredFlight ignored -> throw new IllegalStateException("Expired flight must be replaced");
        };
    }

    private void start(Flight flight, AuctionRequest request, AuctionStarter starter) {
        try {
            CompletionStage<AuctionResult> started = Objects.requireNonNull(
                    starter.start(new StartAuction(request)),
                    "AuctionStarter must return a completion stage"
            );
            started.whenComplete(flight::complete);
        } catch (RuntimeException exception) {
            flight.complete(null, exception);
        }
    }

    private void cleanupIfDue(Instant now) {
        Instant previous = lastCleanupAt.get();
        if (previous != null && now.isBefore(previous.plus(cleanupInterval))) {
            return;
        }
        if (lastCleanupAt.compareAndSet(previous, now)) {
            flights.entrySet().removeIf(entry -> entry.getValue().isExpiredAt(now));
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

        private final AuctionRequestFingerprint fingerprint;
        private final CompletableFuture<AuctionResult> result = new CompletableFuture<>();
        private final AtomicReference<Instant> retainUntil = new AtomicReference<>();

        private Flight(AuctionRequestFingerprint fingerprint) {
            this.fingerprint = fingerprint;
        }

        private CompletionStage<AuctionResult> result() {
            return result;
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
            if (failure == null) {
                if (auctionResult == null) {
                    result.completeExceptionally(new NullPointerException("Auction result must not be null"));
                } else {
                    result.complete(auctionResult);
                }
            } else {
                result.completeExceptionally(failure);
            }
            retainUntil.compareAndSet(null, clock.instant().plus(retention));
        }

        private boolean isExpiredAt(Instant now) {
            Instant expiration = retainUntil.get();
            return expiration != null && !now.isBefore(expiration);
        }
    }

    private sealed interface FlightResolution permits StartFlight, ReuseFlight, ExpiredFlight, ChangedFlight {
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
}
