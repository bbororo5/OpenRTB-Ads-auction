package com.bbororo.rtb.dsp.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bbororo.rtb.dsp.spending.api.SpendingMessages.LeaseSupplySnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdaptiveLeaseDemandPolicyTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-01-01T00:00:10Z");
    private final AdaptiveLeaseDemandPolicy policy =
            new AdaptiveLeaseDemandPolicy(Duration.ofSeconds(2), 100, 1_000);

    @Test
    void requestsMinimumWhenThereIsNoDemandHistory() {
        assertEquals(100, policy.requestedMicros(snapshot(0, 0, 0, Optional.empty()), Optional.empty()));
    }

    @Test
    void keepsEnoughUnexpiredLocalAuthorityWithoutRefilling() {
        var current = snapshot(500, 0, 0, Optional.of(OBSERVED_AT.plusSeconds(5)));
        var previous = snapshotAt(500, 0, 0, Optional.of(OBSERVED_AT.plusSeconds(5)), OBSERVED_AT.minusSeconds(1));

        assertEquals(0, policy.requestedMicros(current, Optional.of(previous)));
    }

    @Test
    void replacesAuthorityThatExpiresInsideCoverageWindow() {
        var current = snapshot(500, 0, 0, Optional.of(OBSERVED_AT.plusSeconds(1)));
        var previous = snapshotAt(500, 0, 0, Optional.of(OBSERVED_AT.plusSeconds(1)), OBSERVED_AT.minusSeconds(1));

        assertEquals(100, policy.requestedMicros(current, Optional.of(previous)));
    }

    @Test
    void projectsRecentReservationsAndClampsTheRequest() {
        var previous = new LeaseSupplySnapshot(
                "campaign-1", 0, 0, 0, 0, 0, 1,
                Optional.of(OBSERVED_AT.plusSeconds(5)), OBSERVED_AT.minusSeconds(1)
        );
        var current = new LeaseSupplySnapshot(
                "campaign-1", 0, 200, 0, 600, 0, 1,
                Optional.of(OBSERVED_AT.plusSeconds(5)), OBSERVED_AT
        );

        assertEquals(1_000, policy.requestedMicros(current, Optional.of(previous)));
    }

    private static LeaseSupplySnapshot snapshot(
            long reusable,
            long reserved,
            long cumulativeReserved,
            Optional<Instant> earliestExpiry
    ) {
        return snapshotAt(reusable, reserved, cumulativeReserved, earliestExpiry, OBSERVED_AT);
    }

    private static LeaseSupplySnapshot snapshotAt(
            long reusable,
            long reserved,
            long cumulativeReserved,
            Optional<Instant> earliestExpiry,
            Instant observedAt
    ) {
        return new LeaseSupplySnapshot(
                "campaign-1", reusable, reserved, 0, cumulativeReserved, 0, 1,
                earliestExpiry, observedAt
        );
    }
}
