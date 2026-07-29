package com.bbororo.rtb.ssp.dspbid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.ssp.contract.AuctionDeadline;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.contract.SspMessages.BidRequestBatch;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcomeKind;
import com.bbororo.rtb.ssp.openrtb.OpenRtb26Codec;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpOpenRtbDspBidExecutorTest {

    @Test
    void fansOutOneOpenRtbRequestAndIsolatesNoBid() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bid-a", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"id":"auction-1","seatbid":[{"bid":[{
                      "id":"bid-1","impid":"imp-1","price":2000.0,
                      "nurl":"http://dsp.test/nurl","lurl":"http://dsp.test/lurl",
                      "burl":"http://dsp.test/burl","exp":2
                    }]}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/bid-b", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            DspBidExecutor executor = new HttpOpenRtbDspBidExecutor(
                    HttpClient.newHttpClient(),
                    new OpenRtb26Codec(),
                    Map.of("dsp-a", base.resolve("/bid-a"), "dsp-b", base.resolve("/bid-b"))
            );

            var responses = executor.requestBids(new BidRequestBatch(
                    "auction-1",
                    new AuctionRequest(
                            "provider-1", "key-1", "request-1", 180,
                            List.of(new AuctionSlot("imp-1", 1_000))
                    ),
                    List.of("dsp-a", "dsp-b"),
                    AuctionDeadline.start(180, System::nanoTime)
            ));

            assertEquals(DspCallOutcomeKind.VALID_BID, responses.outcomes().get(0).kind());
            assertEquals(DspCallOutcomeKind.NO_BID, responses.outcomes().get(1).kind());
            assertTrue(received.get().contains("\"bidfloorcur\":\"KRW\""));
        } finally {
            server.stop(0);
        }
    }
}
