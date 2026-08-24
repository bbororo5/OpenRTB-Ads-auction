package com.bbororo.rtb.dsp.bidding;

import com.bbororo.rtb.dsp.bidding.BiddingMessages.BidDecision;
import com.bbororo.rtb.dsp.bidding.BiddingMessages.CoordinateBid;

/** 절대 기한 안에서 슬롯별 후보 선택·예약·통지 URL 발급을 조정한다. */
public interface BidCoordinator {

    BidDecision coordinate(CoordinateBid command);
}
