package com.bbororo.rtb.dsp.bidding.api;

import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.BidDecision;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.CoordinateBid;

/** 절대 기한 안에서 슬롯별 후보 선택·예약·통지 URL 발급을 조정한다. */
public interface BidCoordinator {

    BidDecision coordinate(CoordinateBid command);
}
