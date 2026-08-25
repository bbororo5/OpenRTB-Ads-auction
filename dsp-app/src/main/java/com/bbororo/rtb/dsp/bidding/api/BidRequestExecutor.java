package com.bbororo.rtb.dsp.bidding.api;

import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.BidExecutionResult;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuthenticatedBidRequest;

/** 인증된 한 입찰 요청의 중복 방지와 입찰 조정을 하나의 컴포넌트 경계로 제공한다. */
public interface BidRequestExecutor {

    BidExecutionResult execute(AuthenticatedBidRequest request);
}
