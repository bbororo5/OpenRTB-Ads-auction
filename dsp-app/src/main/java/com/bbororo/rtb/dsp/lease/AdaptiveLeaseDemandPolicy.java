package com.bbororo.rtb.dsp.lease;

import com.bbororo.rtb.dsp.spending.SpendingMessages.LeaseSupplySnapshot;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** 최근 예약 수요와 가까운 만료를 사용해 다음 리스 요청량을 계산한다. */
public final class AdaptiveLeaseDemandPolicy {

    private final Duration coverage;
    private final long minimumRequestMicros;
    private final long maximumRequestMicros;

    public AdaptiveLeaseDemandPolicy(
            Duration coverage,
            long minimumRequestMicros,
            long maximumRequestMicros
    ) {
        this.coverage = Objects.requireNonNull(coverage, "coverage");
        if (coverage.isZero() || coverage.isNegative()) {
            throw new IllegalArgumentException("coverage must be positive");
        }
        if (minimumRequestMicros <= 0 || maximumRequestMicros < minimumRequestMicros) {
            throw new IllegalArgumentException("request bounds are invalid");
        }
        this.minimumRequestMicros = minimumRequestMicros;
        this.maximumRequestMicros = maximumRequestMicros;
    }

    /** 0은 이번 관측에서 보충이 필요하지 않다는 뜻이다. */
    public long requestedMicros(
            LeaseSupplySnapshot current,
            Optional<LeaseSupplySnapshot> previous
    ) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(previous, "previous");

        long projectedDemand = previous
                .filter(prior -> prior.campaignId().equals(current.campaignId()))
                .map(prior -> projectedDemand(prior, current))
                .orElse(minimumRequestMicros);
        long targetAuthority = clamp(Math.max(
                minimumRequestMicros,
                saturatingAdd(projectedDemand, current.reservedMicros())
        ));
        boolean expiresWithinCoverage = current.earliestExpiry()
                .map(expiry -> !expiry.isAfter(current.observedAt().plus(coverage)))
                .orElse(true);
        long reusableForCoverage = expiresWithinCoverage ? 0L : current.reusableMicros();
        long shortage = Math.max(0L, targetAuthority - reusableForCoverage);
        if (shortage == 0L) {
            return 0L;
        }
        return clamp(Math.max(minimumRequestMicros, shortage));
    }

    private long projectedDemand(LeaseSupplySnapshot previous, LeaseSupplySnapshot current) {
        long elapsedNanos = Duration.between(previous.observedAt(), current.observedAt()).toNanos();
        if (elapsedNanos <= 0L) {
            return minimumRequestMicros;
        }
        long reservedDelta = Math.max(
                0L,
                current.cumulativeReservedMicros() - previous.cumulativeReservedMicros()
        );
        double windows = (double) coverage.toNanos() / elapsedNanos;
        double projection = reservedDelta * windows;
        if (!Double.isFinite(projection) || projection >= maximumRequestMicros) {
            return maximumRequestMicros;
        }
        return Math.max(0L, (long) Math.ceil(projection));
    }

    private long clamp(long amount) {
        return Math.min(maximumRequestMicros, Math.max(0L, amount));
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
