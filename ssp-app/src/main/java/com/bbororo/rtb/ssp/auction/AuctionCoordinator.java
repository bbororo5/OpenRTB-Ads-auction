package com.bbororo.rtb.ssp.auction;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.StartAuction;

/** 절대 기한 안에서 입찰·낙찰·경매 통지를 조정한다. */
public interface AuctionCoordinator {

    AuctionOutcome runAuction(StartAuction command);
}
