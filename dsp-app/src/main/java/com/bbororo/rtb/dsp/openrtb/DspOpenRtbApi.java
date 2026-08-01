package com.bbororo.rtb.dsp.openrtb;

import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuctionNotice;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuthenticatedBidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidResult;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoticeHttpResult;
import java.util.concurrent.CompletionStage;

/** DSP 게이트웨이 뒤에서 OpenRTB 입찰과 통지를 받는 컴포넌트 경계다. */
public interface DspOpenRtbApi {

    BidResult handleBid(AuthenticatedBidRequest request);

    CompletionStage<NoticeHttpResult> handleNotice(AuctionNotice notice);
}
