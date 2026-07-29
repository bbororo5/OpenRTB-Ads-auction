package com.bbororo.rtb.ssp.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProviderApiJsonCodecTest {

    private final ProviderApiJsonCodec codec = new ProviderApiJsonCodec();

    @Test
    void preservesThreeDecimalKrwCpmAsAnInternalInteger() {
        var request = codec.decodeAuctionRequest(("""
                {"providerId":"provider-1","providerKeyId":"key-1",
                 "providerRequestId":"request-1","tmaxMillis":50,
                 "slots":[{"impId":"imp-1","floorCpmKrw":1234.567}]}
                """).getBytes(StandardCharsets.UTF_8));

        assertEquals(1_234_567L, request.slots().getFirst().floorCpmMilliKrw());
    }

    @Test
    void rejectsKrwCpmBeyondThreeDecimalPlaces() {
        byte[] body = ("""
                {"providerId":"provider-1","providerKeyId":"key-1",
                 "providerRequestId":"request-1","tmaxMillis":50,
                 "slots":[{"impId":"imp-1","floorCpmKrw":1234.5678}]}
                """).getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> codec.decodeAuctionRequest(body));
    }
}
