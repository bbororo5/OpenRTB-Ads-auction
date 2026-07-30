package com.bbororo.rtb.ssp.auction;

/** 수락한 경매가 공급자 절대 기한 안에 완성되지 못했을 때 발생한다. */
public final class AuctionDeadlineExceededException extends RuntimeException {

    public AuctionDeadlineExceededException() {
        super("Auction exceeded its absolute deadline");
    }
}
