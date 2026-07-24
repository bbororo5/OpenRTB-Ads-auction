package com.bbororo.rtb.ssp.dspbid;

import com.bbororo.rtb.ssp.contract.SspMessages.BidRequestBatch;
import com.bbororo.rtb.ssp.contract.SspMessages.BidResponses;

/** DSP별 OpenRTB 입찰 통신을 수행한다. */
public interface DspBidExecutor {

    BidResponses requestBids(BidRequestBatch request);
}
