package com.bbororo.rtb.ssp.api;

/** 공급자 요청이 경매 입장 정책을 통과하지 못했음을 API 어댑터에 알린다. */
public final class AuctionRejectedException extends RuntimeException {

    public AuctionRejectedException(String reason) {
        super(reason);
    }
}
