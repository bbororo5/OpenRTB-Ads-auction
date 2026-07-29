package com.bbororo.rtb.ssp.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionResult;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderProof;
import com.bbororo.rtb.ssp.contract.SspMessages.SlotAuctionResult;
import com.bbororo.rtb.ssp.contract.SspMessages.WinningBid;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

    @Test
    void includesTheIssuingRegionRenderUrlInTheAuctionResult() {
        URI dspUrl = URI.create("https://dsp.test/notice");
        URI renderUrl = URI.create("https://region-a.ssp.test/publisher/render");
        var result = new AuctionResult(
                "auction-1",
                List.of(new SlotAuctionResult(
                        new WinningBid(
                                "auction-1/imp-1", "imp-1", "dsp-1", "bid-1", 1_000_000,
                                dspUrl, dspUrl, dspUrl
                        ),
                        new RenderProof("proof-1"),
                        renderUrl
                ))
        );

        String json = new String(codec.encodeAuctionResult(result), StandardCharsets.UTF_8);

        assertTrue(json.contains("\"renderUrl\":\"https://region-a.ssp.test/publisher/render\""));
    }
}
