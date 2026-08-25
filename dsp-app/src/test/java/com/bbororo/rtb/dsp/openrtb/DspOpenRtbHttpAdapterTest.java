package com.bbororo.rtb.dsp.openrtb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.dsp.openrtb.DspOpenRtbHttpAdapter.Request;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuctionNotice;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuthenticatedBidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Bid;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidHttpResult;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidResponse;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoContent;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoticeHttpResult;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.SeatBid;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DspOpenRtbHttpAdapterTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-08-24T00:00:00Z");

    @Test
    void decodesTheProjectProfileAndEncodesAStandardBidResponse() {
        AtomicReference<AuthenticatedBidRequest> captured = new AtomicReference<>();
        var adapter = new DspOpenRtbHttpAdapter(api(request -> {
            captured.set(request);
            return BidResponse.withBids(request.request().id(), List.of(
                    new SeatBid(List.of(bid()))
            ));
        }));

        var response = adapter.handleBid(request("application/json", validRequest()));

        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().get("Content-Type"));
        assertEquals("2.6", response.headers().get("x-openrtb-version"));
        assertEquals("ssp-1", captured.get().sspId());
        assertEquals(RECEIVED_AT, captured.get().receivedAt());
        assertEquals(300, captured.get().request().impressions().getFirst().width());
        assertEquals(250, captured.get().request().impressions().getFirst().height());
        assertEquals(1_000_000L,
                captured.get().request().impressions().getFirst().bidFloorCpmMilliKrw());

        String json = new String(response.body(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"id\":\"auction-1\""));
        assertTrue(json.contains("\"cur\":\"KRW\""));
        assertTrue(json.contains("\"impid\":\"imp-1\""));
        assertTrue(json.contains("\"price\":2.000"));
        assertTrue(json.contains("\"cid\":\"campaign-1\""));
        assertTrue(json.contains("\"crid\":\"creative-1\""));
    }

    @Test
    void mapsReasonlessNoBidToHttp204WithoutABody() {
        var response = new DspOpenRtbHttpAdapter(api(ignored -> NoContent.INSTANCE))
                .handleBid(request("application/json", validRequest()));

        assertEquals(204, response.statusCode());
        assertEquals(0, response.body().length);
        assertEquals("2.6", response.headers().get("x-openrtb-version"));
    }

    @Test
    void deadlineIncludesTimeSpentBeforeJsonDecodingCompletes() {
        var nanos = new AtomicLong(Duration.ofMillis(12).toNanos());
        var remaining = new AtomicReference<Duration>();
        var adapter = new DspOpenRtbHttpAdapter(api(request -> {
            remaining.set(request.deadline().remaining());
            return NoContent.INSTANCE;
        }), nanos::get);
        var request = new Request(
                "POST", "application/json", "2.6", "ssp-1", RECEIVED_AT, 0,
                validRequest().getBytes(StandardCharsets.UTF_8)
        );

        adapter.handleBid(request);

        assertEquals(Duration.ofMillis(37), remaining.get());
    }

    @Test
    void mapsReasonedNoBidToAnHttp200BidResponse() {
        var response = new DspOpenRtbHttpAdapter(api(
                request -> BidResponse.noBid(request.request().id(), 2)
        )).handleBid(request("application/json", validRequest()));

        assertEquals(200, response.statusCode());
        String json = new String(response.body(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"seatbid\":[]"));
        assertTrue(json.contains("\"nbr\":2"));
    }

    @Test
    void assumesJsonWhenContentTypeIsMissing() {
        var response = new DspOpenRtbHttpAdapter(api(ignored -> NoContent.INSTANCE))
                .handleBid(request(null, validRequest()));

        assertEquals(204, response.statusCode());
    }

    @Test
    void returnsHttp400WithoutCallingTheApiForMalformedOrWrongProfileRequests() {
        AtomicInteger calls = new AtomicInteger();
        var adapter = new DspOpenRtbHttpAdapter(api(request -> {
            calls.incrementAndGet();
            return NoContent.INSTANCE;
        }));

        assertEquals(400, adapter.handleBid(request("application/json", "{".getBytes())).statusCode());
        assertEquals(400, adapter.handleBid(request(
                "application/json", validRequest().replace("\"at\":1", "\"at\":2")
        )).statusCode());
        assertEquals(400, adapter.handleBid(request("text/plain", validRequest())).statusCode());
        assertEquals(400, adapter.handleBid(new Request(
                "POST", "application/json", "2.5", "ssp-1", RECEIVED_AT,
                validRequest().getBytes(StandardCharsets.UTF_8)
        )).statusCode());
        assertEquals(0, calls.get());
    }

    private static Request request(String contentType, String body) {
        return request(contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static Request request(String contentType, byte[] body) {
        return new Request("POST", contentType, "2.6", "ssp-1", RECEIVED_AT, body);
    }

    private static String validRequest() {
        return """
                {
                  "id":"auction-1","at":1,"tmax":49,"cur":["KRW"],
                  "imp":[{
                    "id":"imp-1",
                    "banner":{"format":[{"w":300,"h":250}]},
                    "bidfloor":1000.000,"bidfloorcur":"KRW","exp":2
                  }]
                }
                """;
    }

    private static Bid bid() {
        return new Bid(
                "bid-1", "imp-1", "campaign-1", "creative-1", 2_000,
                URI.create("https://dsp.test/nurl"),
                URI.create("https://dsp.test/lurl"),
                URI.create("https://dsp.test/burl"),
                2
        );
    }

    private static DspOpenRtbApi api(BidHandler handler) {
        return new DspOpenRtbApi() {
            @Override
            public BidHttpResult handleBid(AuthenticatedBidRequest request) {
                return handler.handle(request);
            }

            @Override
            public CompletionStage<NoticeHttpResult> handleNotice(AuctionNotice notice) {
                return CompletableFuture.completedFuture(NoticeHttpResult.ACCEPTED);
            }
        };
    }

    @FunctionalInterface
    private interface BidHandler {
        BidHttpResult handle(AuthenticatedBidRequest request);
    }
}
