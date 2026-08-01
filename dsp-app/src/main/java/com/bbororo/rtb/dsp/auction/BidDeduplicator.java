package com.bbororo.rtb.dsp.auction;

import com.bbororo.rtb.dsp.auction.AuctionMessages.BidDecision;
import com.bbororo.rtb.dsp.auction.AuctionMessages.BidExecutionResult;
import com.bbororo.rtb.dsp.auction.AuctionMessages.ExecuteBidOnce;
import java.util.function.Supplier;

/** 같은 SSP 입찰 요청의 최초 실행 하나와 완성 결과 재사용을 소유한다. */
public interface BidDeduplicator {

    BidExecutionResult executeOnce(ExecuteBidOnce command, Supplier<BidDecision> firstExecution);
}
