package com.bbororo.rtb.dsp.proof.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.dsp.proof.api.NoticeVerificationMessages.VerifiedReservationNotice;
import com.bbororo.rtb.dsp.proof.api.ReservationNoticeKind;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NoticeVerificationMessagesTest {

    private static final Instant RESERVED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRES_AT = RESERVED_AT.plusSeconds(5);

    @Test
    void exactDeadlineIsAcceptedAndLaterArrivalIsLate() {
        assertFalse(notice(EXPIRES_AT).arrivedAfterDeadline());
        assertTrue(notice(EXPIRES_AT.plusMillis(1)).arrivedAfterDeadline());
    }

    private static VerifiedReservationNotice notice(Instant receivedAt) {
        return new VerifiedReservationNotice(
                ReservationNoticeKind.BILLING,
                "reservation-1",
                "lease-1",
                "campaign-1",
                "bid-1",
                1_000,
                RESERVED_AT,
                EXPIRES_AT,
                receivedAt
        );
    }
}
