package com.bbororo.rtb.ssp.deduplication;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionWinners;
import com.bbororo.rtb.ssp.contract.SspMessages.StartAuction;
import java.util.concurrent.CompletionStage;

/** 최초 요청에 대해서만 실제 경매를 시작하는 경계다. */
@FunctionalInterface
public interface AuctionStarter {

    CompletionStage<AuctionWinners> start(StartAuction auction);
}
