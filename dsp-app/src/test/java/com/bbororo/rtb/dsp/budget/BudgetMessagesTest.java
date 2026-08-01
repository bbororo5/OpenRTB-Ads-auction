package com.bbororo.rtb.dsp.budget;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseBalance;
import com.bbororo.rtb.dsp.budget.BudgetMessages.TryReserve;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BudgetMessagesTest {

    @Test
    void leaseBalancePreservesFaceValue() {
        assertDoesNotThrow(() -> new LeaseBalance(1_000, 300, 200, 400, 100));
        assertThrows(IllegalArgumentException.class, () -> new LeaseBalance(1_000, 300, 200, 400, 99));
    }

    @Test
    void reservationRequiresARealFutureExpiry() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        assertThrows(IllegalArgumentException.class, () -> new TryReserve(
                "auction-1",
                "imp-1",
                "bid-1",
                "campaign-1",
                1_000,
                now,
                now
        ));
    }
}
