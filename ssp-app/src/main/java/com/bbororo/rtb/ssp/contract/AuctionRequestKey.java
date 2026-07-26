package com.bbororo.rtb.ssp.contract;

import java.util.Objects;

/** 공급자 재시도를 하나의 광고 기회로 식별하는 불변 키다. */
public record AuctionRequestKey(String providerId, String providerRequestId) {

    public AuctionRequestKey {
        requireIdentifier(providerId, "providerId");
        requireIdentifier(providerRequestId, "providerRequestId");
    }

    private static void requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
