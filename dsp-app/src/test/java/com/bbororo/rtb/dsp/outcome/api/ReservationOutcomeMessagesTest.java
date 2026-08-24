package com.bbororo.rtb.dsp.outcome.api;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.MonetaryNoticeEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReservationOutcomeMessagesTest {

    private static final Instant RESERVED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRES_AT = RESERVED_AT.plusSeconds(5);

    @Test
    void monetaryEventRequiresAnInternalTerminalKind() {
        assertThrows(NullPointerException.class, () -> new MonetaryNoticeEvent(
                "event-1",
                null,
                "reservation-1",
                "lease-1",
                "campaign-1",
                1_000,
                EXPIRES_AT,
                RESERVED_AT.plusSeconds(1)
        ));
    }
}
