package com.bbororo.rtb.ssp.deduplication;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionWinners;
import com.bbororo.rtb.ssp.contract.AuctionDeadline;
import java.util.concurrent.CompletionStage;

/** 같은 공급자 요청의 경매를 한 번만 시작하고, 중복 요청은 그 결과를 공유한다. */
public interface AuctionDeduplicator {

    CompletionStage<AuctionWinners> execute(AuctionRequest request, AuctionDeadline deadline, AuctionStarter starter);
}
