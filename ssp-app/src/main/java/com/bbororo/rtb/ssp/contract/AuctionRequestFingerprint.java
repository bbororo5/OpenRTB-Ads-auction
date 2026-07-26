package com.bbororo.rtb.ssp.contract;

import java.util.Objects;

/** SSP가 계산한 경매 요청 비교용 지문 값이다. */
public record AuctionRequestFingerprint(String value) {

    public AuctionRequestFingerprint {
        Objects.requireNonNull(value);
    }
}
