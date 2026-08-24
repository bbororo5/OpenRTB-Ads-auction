package com.bbororo.rtb.dsp.bidding;

import com.bbororo.rtb.dsp.bidding.BiddingMessages.BidDecision;
import com.bbororo.rtb.dsp.bidding.BiddingMessages.BidExecutionResult;
import com.bbororo.rtb.dsp.bidding.BiddingMessages.ExecuteBidOnce;
import java.util.function.Supplier;

/** 같은 SSP 입찰 요청에 최초 실행권을 최대 한 번 부여하고 후속 요청은 기다리지 않고 거절한다. */
public interface BidExecutionGate {

    BidExecutionResult tryExecute(ExecuteBidOnce command, Supplier<BidDecision> firstExecution);
}
