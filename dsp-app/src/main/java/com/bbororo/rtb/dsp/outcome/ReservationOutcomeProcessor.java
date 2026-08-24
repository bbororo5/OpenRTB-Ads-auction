package com.bbororo.rtb.dsp.outcome;

import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.NoticeProcessingResult;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuctionNotice;
import java.util.concurrent.CompletionStage;

/** 통지를 검증하고 금액 사건을 내구 기록한 뒤 예약을 한 번 종결한다. */
public interface ReservationOutcomeProcessor {

    CompletionStage<NoticeProcessingResult> process(AuctionNotice notice);
}
