package com.bbororo.rtb.ssp.claim;

import com.bbororo.rtb.ssp.contract.SspMessages.RenderAcceptance;
import com.bbororo.rtb.ssp.contract.SspMessages.VerifiedRender;

/** 유효한 렌더링을 청구와 내구 전달 작업으로 바꾼다. */
public interface RenderClaimService {

    RenderAcceptance acceptRender(VerifiedRender render);
}
