package com.bbororo.rtb.ssp.deduplication;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.Deduplication;

/** 같은 공급자 요청의 최초 실행 또는 결과 재사용을 판정한다. */
public interface AuctionDeduplicator {

    Deduplication deduplicate(AuctionRequest request);
}
