package com.bbororo.rtb.ssp.deduplication;

import com.bbororo.rtb.ssp.contract.AuctionRequestKey;

/** 이미 처리 중이거나 보존된 요청 키에 다른 경매 내용이 들어왔을 때 발생한다. */
public final class ChangedAuctionRequestException extends RuntimeException {

    public ChangedAuctionRequestException(AuctionRequestKey key) {
        super("Auction request content changed for key: " + key);
    }
}
