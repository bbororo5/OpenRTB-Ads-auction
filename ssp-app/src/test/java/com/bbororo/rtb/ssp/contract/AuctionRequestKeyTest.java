package com.bbororo.rtb.ssp.contract;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AuctionRequestKeyTest {

    @Test
    void keepsProviderAndRequestIdentifiersAsSeparateKeyParts() {
        AuctionRequestKey first = new AuctionRequestKey("provider-a", "request-b-c");
        AuctionRequestKey second = new AuctionRequestKey("provider-a-b", "request-c");

        assertNotEquals(first, second);
    }

    @Test
    void rejectsMissingKeyParts() {
        assertThrows(NullPointerException.class, () -> new AuctionRequestKey(null, "request-1"));
        assertThrows(IllegalArgumentException.class, () -> new AuctionRequestKey("provider-1", " "));
    }
}
