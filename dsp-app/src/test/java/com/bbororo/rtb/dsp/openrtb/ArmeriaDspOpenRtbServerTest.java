package com.bbororo.rtb.dsp.openrtb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.dsp.openrtb.ArmeriaDspOpenRtbServer.Settings;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuctionNotice;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuthenticatedBidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidHttpResult;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoContent;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoticeHttpResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ArmeriaDspOpenRtbServerTest {

    @Test
    void bindsTheNeutralAdapterWithoutRunningBiddingOnTheEventLoop() throws Exception {
        AtomicReference<String> biddingThread = new AtomicReference<>();
        try (var server = server(api(request -> {
            biddingThread.set(Thread.currentThread().getName());
            return NoContent.INSTANCE;
        }), 2)) {
            server.start();

            HttpResponse<byte[]> response = send(server, validRequest(), true);

            assertEquals(204, response.statusCode());
            assertEquals("2.6", response.headers()
                    .firstValue(DspOpenRtbHttpAdapter.VERSION_HEADER).orElseThrow());
            assertTrue(biddingThread.get().startsWith("dsp-bid-worker-"));
        }
    }

    @Test
    void rejectsAnUnauthenticatedRequestBeforeCallingTheDspApi() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var server = server(api(request -> {
            calls.incrementAndGet();
            return NoContent.INSTANCE;
        }), 1)) {
            server.start();

            HttpResponse<byte[]> response = send(server, validRequest(), false);

            assertEquals(401, response.statusCode());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void rejectsAnOversizedRequestBeforeCallingTheDspApi() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var server = server(api(request -> {
            calls.incrementAndGet();
            return NoContent.INSTANCE;
        }), 1, 128)) {
            server.start();

            HttpResponse<byte[]> response = send(server, validRequest(), true);

            assertEquals(413, response.statusCode());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void rejectsImmediatelyWhenAllBidWorkersAreOccupied() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try (var server = server(api(request -> {
            firstEntered.countDown();
            await(releaseFirst);
            return NoContent.INSTANCE;
        }), 1)) {
            server.start();
            CompletableFuture<HttpResponse<byte[]>> first = sendAsync(
                    server, validRequest(), true);
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

            HttpResponse<byte[]> rejected = send(server, validRequest(), true);
            releaseFirst.countDown();

            assertEquals(503, rejected.statusCode());
            assertEquals(204, first.join().statusCode());
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void servesAuthenticatedNoticesOnASeparatedWorker() throws Exception {
        AtomicReference<String> noticeThread = new AtomicReference<>();
        AtomicReference<AuctionNotice> captured = new AtomicReference<>();
        var api = api(
                ignored -> NoContent.INSTANCE,
                notice -> {
                    noticeThread.set(Thread.currentThread().getName());
                    captured.set(notice);
                    return CompletableFuture.completedFuture(NoticeHttpResult.ACCEPTED);
                }
        );
        try (var server = server(api, 1)) {
            server.start();

            HttpResponse<byte[]> response = client().send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://127.0.0.1:" + server.activePort()
                                            + "/notices/billing?token=opaque-token"))
                            .header(ArmeriaDspOpenRtbServer.AUTHENTICATED_SSP_HEADER, "ssp-1")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray()
            );

            assertEquals(204, response.statusCode());
            assertTrue(noticeThread.get().startsWith("dsp-notice-worker-"));
            assertEquals(OpenRtbMessages.NoticeKind.BILLING, captured.get().kind());
            assertEquals("opaque-token", captured.get().opaqueToken());
        }
    }

    private static ArmeriaDspOpenRtbServer server(DspOpenRtbApi api, int bidWorkers) {
        return server(api, bidWorkers, 64 * 1_024);
    }

    private static ArmeriaDspOpenRtbServer server(
            DspOpenRtbApi api,
            int bidWorkers,
            long maxRequestBytes
    ) {
        return new ArmeriaDspOpenRtbServer(
                new Settings(
                        0,
                        "/openrtb/2.6/bid",
                        maxRequestBytes,
                        Duration.ofMillis(180),
                        Duration.ZERO,
                        Duration.ofSeconds(1),
                        bidWorkers
                ),
                new DspOpenRtbHttpAdapter(api),
                Clock.systemUTC()
        );
    }

    private static HttpResponse<byte[]> send(
            ArmeriaDspOpenRtbServer server,
            String body,
            boolean authenticated
    ) throws Exception {
        return client().send(request(server, body, authenticated),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static CompletableFuture<HttpResponse<byte[]>> sendAsync(
            ArmeriaDspOpenRtbServer server,
            String body,
            boolean authenticated
    ) {
        return client().sendAsync(request(server, body, authenticated),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static HttpRequest request(
            ArmeriaDspOpenRtbServer server,
            String body,
            boolean authenticated
    ) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + server.activePort() + "/openrtb/2.6/bid"))
                .timeout(Duration.ofSeconds(1))
                .header("Content-Type", "application/json")
                .header(DspOpenRtbHttpAdapter.VERSION_HEADER, "2.6")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authenticated) {
            request.header(ArmeriaDspOpenRtbServer.AUTHENTICATED_SSP_HEADER, "ssp-1");
        }
        return request.build();
    }

    private static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
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

    private static DspOpenRtbApi api(BidHandler handler) {
        return api(
                handler,
                ignored -> CompletableFuture.completedFuture(NoticeHttpResult.ACCEPTED)
        );
    }

    private static DspOpenRtbApi api(BidHandler handler, NoticeHandler noticeHandler) {
        return new DspOpenRtbApi() {
            @Override
            public BidHttpResult handleBid(AuthenticatedBidRequest request) {
                return handler.handle(request);
            }

            @Override
            public CompletionStage<NoticeHttpResult> handleNotice(AuctionNotice notice) {
                return noticeHandler.handle(notice);
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface BidHandler {
        BidHttpResult handle(AuthenticatedBidRequest request);
    }

    @FunctionalInterface
    private interface NoticeHandler {
        CompletionStage<NoticeHttpResult> handle(AuctionNotice notice);
    }
}
