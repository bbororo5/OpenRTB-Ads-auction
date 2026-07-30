package com.bbororo.rtb.ssp.deduplication;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionResult;
import com.bbororo.rtb.ssp.contract.SspMessages.StartAuction;
import java.util.concurrent.CompletionStage;

/** 최초 요청에 대해서만 경매부터 최종 공급자 응답 생성까지 시작하는 경계다. */
@FunctionalInterface
public interface AuctionStarter {

    CompletionStage<AuctionResult> start(StartAuction auction);
}
