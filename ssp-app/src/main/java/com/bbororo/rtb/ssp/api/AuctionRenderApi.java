package com.bbororo.rtb.ssp.api;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionResult;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderAcceptance;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderCompleted;

/** 공급자 경매와 클라이언트 렌더링 완료 통지의 진입 계약이다. */
public interface AuctionRenderApi {

    AuctionResult auction(AuctionRequest request);

    RenderAcceptance completeRender(RenderCompleted completed);
}
