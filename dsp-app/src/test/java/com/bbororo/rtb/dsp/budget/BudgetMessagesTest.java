package com.bbororo.rtb.dsp.budget;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bbororo.rtb.dsp.budget.BudgetMessages.CommitReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ExpireReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseBalance;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReleaseReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationReference;
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

    @Test
    void finalizationCarriesTheCompleteReservationIdentity() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var reference = new ReservationReference("campaign-1", "lease-1", "reservation-1");

        var release = new ReleaseReservation(reference, 1_000, "event-1", now);
        var commit = new CommitReservation(reference, 1_000, "event-2", now);
        var expire = new ExpireReservation(reference, 1_000, "event-3", now);

        assertEquals(reference, release.reservation());
        assertEquals(reference, commit.reservation());
        assertEquals(reference, expire.reservation());
    }

    @Test
    void finalizationRequiresACompleteReservationIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReservationReference("campaign-1", "", "reservation-1"));
    }
}
