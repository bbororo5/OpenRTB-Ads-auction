package com.bbororo.rtb.ssp.dspbid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.ssp.contract.AuctionDeadline;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.contract.SspMessages.BidRequestBatch;
import com.bbororo.rtb.ssp.contract.SspMessages.BidResponses;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcomeKind;
import com.bbororo.rtb.ssp.openrtb.OpenRtb26Codec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpOpenRtbDspBidExecutorTest {

    @Test
    void fansOutOneOpenRtbRequestAndIsolatesNoBid() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        AtomicReference<String> receivedVersion = new AtomicReference<>();
        try (TestServer server = new TestServer()) {
            server.context("/bid-a", exchange -> {
                received.set(readRequest(exchange));
                receivedVersion.set(exchange.getRequestHeaders().getFirst("x-openrtb-version"));
                respond(exchange, 200, "application/json; charset=utf-8", validBid());
            });
            server.context("/bid-b", exchange -> respond(exchange, 204, null, new byte[0]));
            server.start();

            DspBidExecutor executor = executor(
                    Map.of("dsp-a", server.uri("/bid-a"), "dsp-b", server.uri("/bid-b")),
                    Duration.ofMillis(100),
                    64,
                    64 * 1_024
            );

            BidResponses responses = executor.requestBids(batch("dsp-a", "dsp-b"));

            assertEquals(DspCallOutcomeKind.VALID_BID, responses.outcomes().get(0).kind());
            assertEquals(DspCallOutcomeKind.NO_BID, responses.outcomes().get(1).kind());
            assertTrue(received.get().contains("\"bidfloorcur\":\"KRW\""));
            assertTrue(received.get().contains("\"tmax\":100"));
            assertEquals("2.6", receivedVersion.get());
        }
    }

    @Test
    void advertisesOnlyTheDspCallBudgetRemainingInsideTheAuctionDeadline() throws Exception {
        AtomicLong monotonicNanos = new AtomicLong();
        AuctionDeadline deadline = AuctionDeadline.start(180, monotonicNanos::get);
        monotonicNanos.set(Duration.ofMillis(50).toNanos());
        AtomicReference<String> received = new AtomicReference<>();
        try (TestServer server = new TestServer()) {
            server.context("/bid", exchange -> {
                received.set(readRequest(exchange));
                respond(exchange, 200, "application/json", validBid());
            });
            server.start();

            DspBidExecutor executor = executor(
                    Map.of("dsp-a", server.uri("/bid")),
                    Duration.ofMillis(180),
                    64,
                    64 * 1_024
            );

            executor.requestBids(batch(deadline, "dsp-a"));

            assertTrue(received.get().contains("\"tmax\":129"));
        }
    }

    @Test
    void timesOutOnlyTheSlowDsp() throws Exception {
        try (TestServer server = new TestServer()) {
            server.context("/fast", exchange ->
                    respond(exchange, 200, "application/json", validBid()));
            server.context("/slow", exchange -> {
                sleep(100);
                respond(exchange, 200, "application/json", validBid());
            });
            server.start();
            DspBidExecutor executor = executor(
                    Map.of("fast", server.uri("/fast"), "slow", server.uri("/slow")),
                    Duration.ofMillis(25),
                    64,
                    64 * 1_024
            );

            BidResponses responses = executor.requestBids(batch("fast", "slow"));

            assertEquals(DspCallOutcomeKind.VALID_BID, responses.outcomes().get(0).kind());
            assertEquals(DspCallOutcomeKind.TIMEOUT, responses.outcomes().get(1).kind());
        }
    }

    @Test
    void isolatesOneDspConnectionFailure() throws Exception {
        try (TestServer server = new TestServer()) {
            server.context("/valid", exchange ->
                    respond(exchange, 200, "application/json", validBid()));
            server.context("/broken", HttpExchange::close);
            server.start();
            DspBidExecutor executor = executor(
                    Map.of("valid", server.uri("/valid"), "broken", server.uri("/broken")),
                    Duration.ofMillis(100),
                    64,
                    64 * 1_024
            );

            BidResponses responses = executor.requestBids(batch("valid", "broken"));

            assertEquals(DspCallOutcomeKind.VALID_BID, responses.outcomes().get(0).kind());
            assertEquals(DspCallOutcomeKind.ERROR, responses.outcomes().get(1).kind());
        }
    }

    @Test
    void doesNotRetryARejectedBidRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (TestServer server = new TestServer()) {
            server.context("/rejected", exchange -> {
                calls.incrementAndGet();
                respond(exchange, 503, null, new byte[0]);
            });
            server.start();
            DspBidExecutor executor = executor(
                    Map.of("dsp-a", server.uri("/rejected")),
                    Duration.ofMillis(100),
                    64,
                    64 * 1_024
            );

            BidResponses responses = executor.requestBids(batch("dsp-a"));

            assertEquals(DspCallOutcomeKind.ERROR, responses.outcomes().getFirst().kind());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void rejectsWrongContentTypeAndOversizedBodyWithoutLosingAnotherDsp() throws Exception {
        byte[] oversized = new byte[2_048];
        try (TestServer server = new TestServer()) {
            server.context("/valid", exchange ->
                    respond(exchange, 200, "application/json", validBid()));
            server.context("/wrong-type", exchange ->
                    respond(exchange, 200, "text/plain", validBid()));
            server.context("/oversized", exchange ->
                    respond(exchange, 200, "application/json", oversized));
            server.start();
            DspBidExecutor executor = executor(
                    Map.of(
                            "valid", server.uri("/valid"),
                            "wrong-type", server.uri("/wrong-type"),
                            "oversized", server.uri("/oversized")
                    ),
                    Duration.ofMillis(100),
                    64,
                    1_024
            );

            BidResponses responses = executor.requestBids(batch("valid", "wrong-type", "oversized"));

            assertEquals(DspCallOutcomeKind.VALID_BID, responses.outcomes().get(0).kind());
            assertEquals(DspCallOutcomeKind.INVALID_BID, responses.outcomes().get(1).kind());
            assertEquals(DspCallOutcomeKind.INVALID_BID, responses.outcomes().get(2).kind());
        }
    }

    @Test
    void rejectsBeyondOneDspCapacityWithoutQueueing() throws Exception {
        CountDownLatch firstCallEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCall = new CountDownLatch(1);
        try (TestServer server = new TestServer();
             ExecutorService caller = Executors.newVirtualThreadPerTaskExecutor()) {
            server.context("/bid", exchange -> {
                firstCallEntered.countDown();
                await(releaseFirstCall);
                respond(exchange, 200, "application/json", validBid());
            });
            server.start();
            DspBidExecutor executor = executor(
                    Map.of("dsp-a", server.uri("/bid")),
                    Duration.ofMillis(150),
                    1,
                    64 * 1_024
            );

            CompletableFuture<BidResponses> first = CompletableFuture.supplyAsync(
                    () -> executor.requestBids(batch("dsp-a")),
                    caller
            );
            assertTrue(firstCallEntered.await(1, TimeUnit.SECONDS));

            BidResponses rejected = executor.requestBids(batch("dsp-a"));
            releaseFirstCall.countDown();

            assertEquals(DspCallOutcomeKind.ERROR, rejected.outcomes().getFirst().kind());
            assertEquals(DspCallOutcomeKind.VALID_BID, first.join().outcomes().getFirst().kind());
        } finally {
            releaseFirstCall.countDown();
        }
    }

    private static DspBidExecutor executor(
            Map<String, URI> endpoints,
            Duration timeout,
            int maxInFlight,
            int maxResponseBytes
    ) {
        Map<String, DspBidChannel> channels = new LinkedHashMap<>();
        endpoints.forEach((dspId, endpoint) -> channels.put(
                dspId,
                new DspBidChannel(endpoint, HttpClient.newHttpClient(), maxInFlight)
        ));
        return new HttpOpenRtbDspBidExecutor(
                new OpenRtb26Codec(),
                channels,
                timeout,
                maxResponseBytes
        );
    }

    private static BidRequestBatch batch(String... dspIds) {
        return batch(AuctionDeadline.start(180, System::nanoTime), dspIds);
    }

    private static BidRequestBatch batch(AuctionDeadline deadline, String... dspIds) {
        return new BidRequestBatch(
                "auction-1",
                new AuctionRequest(
                        "provider-1", "key-1", "request-1", 180,
                        List.of(new AuctionSlot("imp-1", 300, 250, 1_000))
                ),
                List.of(dspIds),
                deadline
        );
    }

    private static byte[] validBid() {
        return """
                {"id":"auction-1","cur":"KRW","seatbid":[{"bid":[{
                  "id":"bid-1","impid":"imp-1","price":2000.0,
                  "nurl":"http://dsp.test/nurl","lurl":"http://dsp.test/lurl",
                  "burl":"http://dsp.test/burl","exp":2
                }]}]}
                """.getBytes(StandardCharsets.UTF_8);
    }

    private static String readRequest(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            String contentType,
            byte[] body
    ) throws IOException {
        exchange.getRequestBody().readAllBytes();
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class TestServer implements AutoCloseable {

        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        private TestServer() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
        }

        private void context(String path, com.sun.net.httpserver.HttpHandler handler) {
            server.createContext(path, handler);
        }

        private void start() {
            server.start();
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
