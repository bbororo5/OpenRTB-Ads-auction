package com.bbororo.rtb.dsp.notification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.MonetaryNoticeEvent;
import com.bbororo.rtb.dsp.notification.NotificationMessages.VerifiedReservationNotice;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoticeKind;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationMessagesTest {

    private static final Instant RESERVED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRES_AT = RESERVED_AT.plusSeconds(5);

    @Test
    void winNoticeCannotBecomeAMonetaryEvent() {
        assertThrows(IllegalArgumentException.class, () -> new MonetaryNoticeEvent(
                "event-1",
                NoticeKind.WIN,
                "reservation-1",
                "lease-1",
                "campaign-1",
                "bid-1",
                1_000,
                EXPIRES_AT,
                RESERVED_AT.plusSeconds(1)
        ));
    }

    @Test
    void exactDeadlineIsAcceptedAndLaterArrivalIsLate() {
        assertFalse(notice(EXPIRES_AT).arrivedAfterDeadline());
        assertTrue(notice(EXPIRES_AT.plusMillis(1)).arrivedAfterDeadline());
    }

    private static VerifiedReservationNotice notice(Instant receivedAt) {
        return new VerifiedReservationNotice(
                NoticeKind.BILLING,
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
