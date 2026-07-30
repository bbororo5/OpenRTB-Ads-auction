package com.bbororo.rtb.ssp.deduplication;

/** 중복 방지 메모리가 가득 차 새로운 경매 키를 보존할 수 없을 때 발생한다. */
public final class AuctionDeduplicationCapacityException extends RuntimeException {

    public AuctionDeduplicationCapacityException(int maximumEntries) {
        super("Auction deduplication capacity is exhausted: " + maximumEntries);
    }
}
