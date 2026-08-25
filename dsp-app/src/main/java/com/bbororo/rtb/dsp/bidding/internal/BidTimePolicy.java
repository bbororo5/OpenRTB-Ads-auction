package com.bbororo.rtb.dsp.bidding.internal;

import com.bbororo.rtb.dsp.contract.AuctionDeadline;

/** 다음 작업을 시작하고 외부 응답을 만들 시간 여유가 있는지 판정한다. */
public interface BidTimePolicy {

    boolean canStartSlot(AuctionDeadline deadline);

    boolean canStartCandidate(AuctionDeadline deadline);

    boolean canPublish(AuctionDeadline deadline);
}
