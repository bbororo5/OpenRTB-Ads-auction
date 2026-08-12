package com.bbororo.rtb.dsp.notification;

import com.bbororo.rtb.dsp.notification.NoticeIssuanceMessages.ComposeReservationNoticeClaims;
import com.bbororo.rtb.dsp.notification.NoticeIssuanceMessages.ReservationNoticeClaims;
import java.util.Objects;

/** 인증된 거래 상대와 예약 사실을 봉인 전 공통 내용으로 만든다. */
public final class ReservationNoticeClaimsFactory {

    public ReservationNoticeClaims compose(ComposeReservationNoticeClaims command) {
        Objects.requireNonNull(command, "command");
        var reservation = command.reservation();
        return new ReservationNoticeClaims(
                command.authenticatedSspId(),
                command.regionId(),
                reservation.reservationId(),
                reservation.leaseId(),
                reservation.campaignId(),
                reservation.bidId(),
                reservation.impressionAmountMicros(),
                reservation.reservedAt(),
                reservation.expiresAt()
        );
    }
}
