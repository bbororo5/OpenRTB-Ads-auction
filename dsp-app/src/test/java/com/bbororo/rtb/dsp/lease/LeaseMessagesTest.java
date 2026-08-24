package com.bbororo.rtb.dsp.lease;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bbororo.rtb.dsp.spending.api.SpendingMessages.LeaseSupplySnapshot;
import com.bbororo.rtb.dsp.lease.LeaseMessages.ClaimDueSettlements;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementAmounts;
import com.bbororo.rtb.dsp.lease.LeaseMessages.RefillLease;
import com.bbororo.rtb.dsp.outcome.LeaseOutcomeView.LeaseOutcomeSummary;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LeaseMessagesTest {

    @Test
    void usageAndSettlementBothPreserveLeaseFaceValue() {
        assertDoesNotThrow(() -> new LeaseOutcomeSummary(
                "lease-1", 1_000, 600, 300, 100, true
        ));
        assertDoesNotThrow(() -> new LeaseSettlementAmounts(
                "lease-1", 1, 1_000, 600, 300, 100
        ));

        assertThrows(
                IllegalArgumentException.class,
                () -> new LeaseOutcomeSummary("lease-1", 1_000, 600, 300, 99, true)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new LeaseSettlementAmounts("lease-1", 1, 1_000, 600, 300, 99)
        );
    }

    @Test
    void refillCarriesLocalDemandWithoutOwningLedgerTime() {
        var position = new LeaseSupplySnapshot(
                "campaign-1", 300, 100, 50, 1_000, 550,
                2, Optional.of(Instant.parse("2026-01-01T00:00:05Z")),
                Instant.parse("2026-01-01T00:00:01Z")
        );

        var refill = new RefillLease("request-1", "instance-1", position, 500);

        assertDoesNotThrow(() -> refill);
        org.junit.jupiter.api.Assertions.assertEquals("campaign-1", refill.campaignId());
    }

    @Test
    void settlementClaimMustBeBounded() {
        assertDoesNotThrow(() -> new ClaimDueSettlements(
                "worker-1", 32, Duration.ofSeconds(1)
        ));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ClaimDueSettlements("worker-1", 0, Duration.ofSeconds(1))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ClaimDueSettlements("worker-1", 1, Duration.ZERO)
        );
    }
}
