package com.bbororo.rtb.ssp.openrtb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.ssp.contract.AuctionDeadline;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.contract.SspMessages.BidRequestBatch;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcomeKind;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenRtb26CodecTest {

    private final OpenRtb26Codec codec = new OpenRtb26Codec();

    @Test
    void encodesTheAuctionAsAnOpenRtbRequest() {
        byte[] json = codec.encodeBidRequest(new BidRequestBatch(
                "auction-1",
                new AuctionRequest(
                        "provider-1", "key-1", "request-1", 50,
                        List.of(new AuctionSlot("imp-1", 1_000_000))
                ),
                List.of("dsp-1"),
                AuctionDeadline.start(50, System::nanoTime)
        ), 50);
        String text = new String(json, StandardCharsets.UTF_8);

        assertTrue(text.contains("\"id\":\"auction-1\""));
        assertTrue(text.contains("\"at\":1"));
        assertTrue(text.contains("\"tmax\":50"));
        assertTrue(text.contains("\"bidfloor\":1000.000"));
        assertTrue(text.contains("\"bidfloorcur\":\"KRW\""));
        assertTrue(text.contains("\"exp\":2"));
    }

    @Test
    void decodesAValidOpenRtbBidWithoutSharingAnInternalModel() {
        String json = """
                {
                  "id": "auction-1",
                  "cur": "KRW",
                  "seatbid": [{"bid": [{
                    "id": "bid-1",
                    "impid": "imp-1",
                    "price": 2000.125,
                    "nurl": "https://dsp.test/nurl/1",
                    "lurl": "https://dsp.test/lurl/1",
                    "burl": "https://dsp.test/burl/1",
                    "exp": 2
                  }]}]
                }
                """;

        var result = codec.decodeBidResponse("dsp-1", batch(), json.getBytes(StandardCharsets.UTF_8));

        assertEquals(DspCallOutcomeKind.VALID_BID, result.kind());
        assertEquals(2_000_125L, result.bids().getFirst().cpmMilliKrw());
        assertEquals("imp-1", result.bids().getFirst().impId());
    }

    @Test
    void rejectsABidWhoseCurrencyIsNotTheContractedKrw() {
        String json = validBidJson("\"impid\":\"imp-1\",\"exp\":2")
                .replace("\"cur\":\"KRW\"", "\"cur\":\"USD\"");

        assertEquals(
                DspCallOutcomeKind.INVALID_BID,
                codec.decodeBidResponse("dsp-1", batch(), json.getBytes(StandardCharsets.UTF_8)).kind()
        );
    }

    @Test
    void rejectsABidWithoutAnExplicitCurrency() {
        String json = validBidJson("\"impid\":\"imp-1\",\"exp\":2")
                .replace("\"cur\":\"KRW\",", "");

        assertEquals(
                DspCallOutcomeKind.INVALID_BID,
                codec.decodeBidResponse("dsp-1", batch(), json.getBytes(StandardCharsets.UTF_8)).kind()
        );
    }

    @Test
    void rejectsAMismatchedAuctionId() {
        byte[] body = "{\"id\":\"another-auction\",\"seatbid\":[]}".getBytes(StandardCharsets.UTF_8);

        assertEquals(
                DspCallOutcomeKind.INVALID_BID,
                codec.decodeBidResponse("dsp-1", batch(), body).kind()
        );
    }

    @Test
    void rejectsAnOpenRtbBidBeyondThreeDecimalPlaces() {
        String json = """
                {"id":"auction-1","cur":"KRW","seatbid":[{"bid":[{
                  "id":"bid-1","impid":"imp-1","price":2000.0001,
                  "nurl":"https://dsp.test/nurl/1",
                  "lurl":"https://dsp.test/lurl/1",
                  "burl":"https://dsp.test/burl/1"
                }]}]}
                """;

        assertEquals(
                DspCallOutcomeKind.INVALID_BID,
                codec.decodeBidResponse("dsp-1", batch(), json.getBytes(StandardCharsets.UTF_8)).kind()
        );
    }

    @Test
    void rejectsABidForAnImpressionOutsideTheRequest() {
        String json = validBidJson("\"impid\":\"unknown\",\"exp\":2");

        assertEquals(
                DspCallOutcomeKind.INVALID_BID,
                codec.decodeBidResponse("dsp-1", batch(), json.getBytes(StandardCharsets.UTF_8)).kind()
        );
    }

    @Test
    void rejectsABidWithoutTheContractedRenderExpiry() {
        String json = validBidJson("\"impid\":\"imp-1\",\"exp\":3");

        assertEquals(
                DspCallOutcomeKind.INVALID_BID,
                codec.decodeBidResponse("dsp-1", batch(), json.getBytes(StandardCharsets.UTF_8)).kind()
        );
    }

    @Test
    void rejectsTheWholeDspResponseWhenOneBidIsInvalid() {
        String json = """
                {"id":"auction-1","cur":"KRW","seatbid":[{"bid":[{
                  "id":"valid","impid":"imp-1","price":2000.000,
                  "nurl":"https://dsp.test/nurl/1",
                  "lurl":"https://dsp.test/lurl/1",
                  "burl":"https://dsp.test/burl/1","exp":2
                },{
                  "id":"invalid","impid":"unknown","price":3000.000,
                  "nurl":"https://dsp.test/nurl/2",
                  "lurl":"https://dsp.test/lurl/2",
                  "burl":"https://dsp.test/burl/2","exp":2
                }]}]}
                """;

        assertEquals(
                DspCallOutcomeKind.INVALID_BID,
                codec.decodeBidResponse("dsp-1", batch(), json.getBytes(StandardCharsets.UTF_8)).kind()
        );
    }

    private static BidRequestBatch batch() {
        return new BidRequestBatch(
                "auction-1",
                new AuctionRequest(
                        "provider-1", "key-1", "request-1", 50,
                        List.of(new AuctionSlot("imp-1", 1_000))
                ),
                List.of("dsp-1"),
                AuctionDeadline.start(50, System::nanoTime)
        );
    }

    private static String validBidJson(String impressionAndExpiry) {
        return """
                {"id":"auction-1","cur":"KRW","seatbid":[{"bid":[{
                  "id":"bid-1",%s,"price":2000.000,
                  "nurl":"https://dsp.test/nurl/1",
                  "lurl":"https://dsp.test/lurl/1",
                  "burl":"https://dsp.test/burl/1"
                }]}]}
                """.formatted(impressionAndExpiry);
    }
}
