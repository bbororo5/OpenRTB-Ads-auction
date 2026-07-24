package com.bbororo.rtb.ssp.winner;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionWinners;
import com.bbororo.rtb.ssp.contract.SspMessages.EligibleBids;

/** 외부 I/O 없이 유효 입찰로부터 1가격 낙찰자를 결정한다. */
public interface WinnerSelector {

    AuctionWinners selectWinners(EligibleBids bids);
}
