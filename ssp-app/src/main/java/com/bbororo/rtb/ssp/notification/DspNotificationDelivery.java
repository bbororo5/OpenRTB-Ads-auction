package com.bbororo.rtb.ssp.notification;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionNotice;
import java.time.Instant;
import java.util.List;

/** 경매 통지와 내구 과금 통지의 전달·재시도를 소유한다. */
public interface DspNotificationDelivery {

    void sendAuctionNotices(List<AuctionNotice> notices);

    BillingDeliveryAttempt deliverDueBilling(Instant now);
}
