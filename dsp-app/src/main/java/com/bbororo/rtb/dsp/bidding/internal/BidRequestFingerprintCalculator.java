package com.bbororo.rtb.dsp.bidding.internal;

import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.BidRequestFingerprint;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidRequest;

/** 검증된 OpenRTB 내부 요청의 입찰 의미를 결정적 지문으로 바꾼다. */
public interface BidRequestFingerprintCalculator {

    BidRequestFingerprint calculate(BidRequest request);
}
