package com.bbororo.rtb.ssp.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.ssp.auction.AuctionDeadlineExceededException;
import com.bbororo.rtb.ssp.contract.AuctionRequestKey;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionResult;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderAcceptance;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderCompleted;
import com.bbororo.rtb.ssp.deduplication.AuctionDeduplicationCapacityException;
import com.bbororo.rtb.ssp.deduplication.ChangedAuctionRequestException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProviderHttpServerTest {

    private static final String AUCTION_JSON = """
            {"providerId":"provider-1","providerKeyId":"key-1",
             "providerRequestId":"request-1","tmaxMillis":50,
             "slots":[{"impId":"imp-1","floorCpmKrw":1000}]}
            """;

    @Test
    void exposesProviderAuctionAndUsesServerTimeForRenderCompletion() throws Exception {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        AtomicReference<RenderCompleted> completed = new AtomicReference<>();
        AuctionRenderApi api = new AuctionRenderApi() {
            @Override
            public AuctionResult auction(AuctionRequest request) {
                assertEquals("provider-1", request.providerId());
                return new AuctionResult(
                        "auction-1",
                        List.of(),
                        URI.create("https://region-a.ssp.test/publisher/render")
                );
            }

            @Override
            public RenderAcceptance completeRender(RenderCompleted render) {
                completed.set(render);
                return RenderAcceptance.ACCEPTED;
            }
        };
        try (ProviderHttpServer server = new ProviderHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                api,
                new ProviderApiJsonCodec(),
                Clock.fixed(now, ZoneOffset.UTC)
        )) {
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.port());
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> auction = client.send(
                    post(base.resolve("/publisher/auction"), AUCTION_JSON),
                    HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> render = client.send(
                    post(base.resolve("/publisher/render"), "{\"renderProof\":\"proof-1\"}"),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, auction.statusCode());
            assertTrue(auction.body().contains("\"auctionId\":\"auction-1\""));
            assertEquals(204, render.statusCode());
            assertEquals(now, completed.get().receivedAt());
        }
    }

    @Test
    void mapsRenderClaimResultsWithoutHidingStorageFailure() throws Exception {
        AtomicReference<RenderAcceptance> result = new AtomicReference<>(RenderAcceptance.DUPLICATE);
        AuctionRenderApi api = new AuctionRenderApi() {
            @Override
            public AuctionResult auction(AuctionRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public RenderAcceptance completeRender(RenderCompleted render) {
                return result.get();
            }
        };
        try (ProviderHttpServer server = new ProviderHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                api,
                new ProviderApiJsonCodec(),
                Clock.systemUTC()
        )) {
            server.start();
            URI renderUrl = URI.create(
                    "http://127.0.0.1:" + server.port() + "/publisher/render"
            );
            HttpClient client = HttpClient.newHttpClient();

            assertEquals(204, sendRender(client, renderUrl).statusCode());
            result.set(RenderAcceptance.REJECTED);
            assertEquals(400, sendRender(client, renderUrl).statusCode());
            result.set(RenderAcceptance.RETRY_LATER);
            assertEquals(503, sendRender(client, renderUrl).statusCode());
        }
    }

    @Test
    void mapsAuctionConflictCapacityAndDeadlineToDistinctHttpFailures() throws Exception {
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        AuctionRenderApi api = new AuctionRenderApi() {
            @Override
            public AuctionResult auction(AuctionRequest request) {
                throw failure.get();
            }

            @Override
            public RenderAcceptance completeRender(RenderCompleted render) {
                return RenderAcceptance.ACCEPTED;
            }
        };
        try (ProviderHttpServer server = new ProviderHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                api,
                new ProviderApiJsonCodec(),
                Clock.systemUTC()
        )) {
            server.start();
            URI auctionUrl = URI.create(
                    "http://127.0.0.1:" + server.port() + "/publisher/auction"
            );
            HttpClient client = HttpClient.newHttpClient();

            failure.set(new CompletionException(new ChangedAuctionRequestException(
                    new AuctionRequestKey("provider-1", "request-1")
            )));
            assertStatusAndCode(
                    sendAuction(client, auctionUrl),
                    409,
                    "AUCTION_REQUEST_CONFLICT"
            );

            failure.set(new CompletionException(
                    new AuctionDeduplicationCapacityException(10_000)
            ));
            assertStatusAndCode(
                    sendAuction(client, auctionUrl),
                    503,
                    "SERVER_OVERLOADED"
            );

            failure.set(new CompletionException(new AuctionDeadlineExceededException()));
            assertStatusAndCode(
                    sendAuction(client, auctionUrl),
                    504,
                    "AUCTION_DEADLINE_EXCEEDED"
            );

            failure.set(new CompletionException(new IllegalStateException("unexpected")));
            assertStatusAndCode(sendAuction(client, auctionUrl), 500, "INTERNAL_ERROR");
        }
    }

    @Test
    void rejectsUnsupportedOrOversizedRequestsBeforeCallingTheApi() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AuctionRenderApi api = new AuctionRenderApi() {
            @Override
            public AuctionResult auction(AuctionRequest request) {
                calls.incrementAndGet();
                throw new AssertionError("rejected request must not reach the API");
            }

            @Override
            public RenderAcceptance completeRender(RenderCompleted render) {
                calls.incrementAndGet();
                throw new AssertionError("rejected request must not reach the API");
            }
        };
        try (ProviderHttpServer server = new ProviderHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                api,
                new ProviderApiJsonCodec(),
                Clock.systemUTC(),
                new ProviderHttpLimits(8, 1_024, 1_024)
        )) {
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.port());
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> method = client.send(
                    HttpRequest.newBuilder(base.resolve("/publisher/auction")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> mediaType = client.send(
                    HttpRequest.newBuilder(base.resolve("/publisher/auction"))
                            .POST(HttpRequest.BodyPublishers.ofString(AUCTION_JSON))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> oversizedAuction = client.send(
                    post(
                            base.resolve("/publisher/auction"),
                            AUCTION_JSON + " ".repeat(1_024)
                    ),
                    HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> oversizedRender = client.send(
                    post(
                            base.resolve("/publisher/render"),
                            "{\"renderProof\":\"" + "a".repeat(1_024) + "\"}"
                    ),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(405, method.statusCode());
            assertEquals("POST", method.headers().firstValue("Allow").orElseThrow());
            assertStatusAndCode(mediaType, 415, "UNSUPPORTED_MEDIA_TYPE");
            assertStatusAndCode(oversizedAuction, 413, "REQUEST_TOO_LARGE");
            assertStatusAndCode(oversizedRender, 413, "REQUEST_TOO_LARGE");
            assertEquals(0, calls.get());
        }
    }

    @Test
    void rejectsExcessWorkWithoutQueueingBehindAnAcceptedRequest() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger renderCalls = new AtomicInteger();
        AuctionRenderApi api = new AuctionRenderApi() {
            @Override
            public AuctionResult auction(AuctionRequest request) {
                entered.countDown();
                await(release);
                return new AuctionResult(
                        "auction-1",
                        List.of(),
                        URI.create("https://ssp.test/render")
                );
            }

            @Override
            public RenderAcceptance completeRender(RenderCompleted render) {
                renderCalls.incrementAndGet();
                return RenderAcceptance.ACCEPTED;
            }
        };
        try (ProviderHttpServer server = new ProviderHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                api,
                new ProviderApiJsonCodec(),
                Clock.systemUTC(),
                new ProviderHttpLimits(1, 1_024, 1_024)
        )) {
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.port());
            HttpClient client = HttpClient.newHttpClient();
            CompletableFuture<HttpResponse<String>> accepted = client.sendAsync(
                    post(base.resolve("/publisher/auction"), AUCTION_JSON),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            HttpResponse<String> excess = sendRender(
                    client,
                    base.resolve("/publisher/render")
            );
            release.countDown();

            assertStatusAndCode(excess, 503, "SERVER_OVERLOADED");
            assertEquals(200, accepted.get(1, TimeUnit.SECONDS).statusCode());
            assertEquals(0, renderCalls.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void exposesLivenessAndReadinessOnlyWhileAcceptingNewRequests() throws Exception {
        try (ProviderHttpServer server = new ProviderHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                acceptingApi(),
                new ProviderApiJsonCodec(),
                Clock.systemUTC()
        )) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + server.port());

            assertEquals(204, client.send(
                    HttpRequest.newBuilder(base.resolve("/health/live")).GET().build(),
                    HttpResponse.BodyHandlers.discarding()
            ).statusCode());
            assertEquals(204, client.send(
                    HttpRequest.newBuilder(base.resolve("/health/ready")).GET().build(),
                    HttpResponse.BodyHandlers.discarding()
            ).statusCode());
        }
    }

    @Test
    void drainsAcceptedRequestsBeforeClosingAndRejectsNewBusinessRequests() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AuctionRenderApi api = new AuctionRenderApi() {
            @Override
            public AuctionResult auction(AuctionRequest request) {
                entered.countDown();
                await(release);
                return new AuctionResult(
                        "auction-1",
                        List.of(),
                        URI.create("https://ssp.test/render")
                );
            }

            @Override
            public RenderAcceptance completeRender(RenderCompleted render) {
                return RenderAcceptance.ACCEPTED;
            }
        };
        ProviderHttpServer server = new ProviderHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                api,
                new ProviderApiJsonCodec(),
                Clock.systemUTC()
        );
        try {
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.port());
            HttpClient client = HttpClient.newHttpClient();
            CompletableFuture<HttpResponse<String>> accepted = client.sendAsync(
                    post(base.resolve("/publisher/auction"), AUCTION_JSON),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            CompletableFuture<Void> closing = CompletableFuture.runAsync(server::close);
            waitUntilDraining(client, base);
            assertStatusAndCode(
                    sendAuction(client, base.resolve("/publisher/auction")),
                    503,
                    "SERVER_DRAINING"
            );

            release.countDown();
            assertEquals(200, accepted.get(1, TimeUnit.SECONDS).statusCode());
            closing.get(1, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            server.close();
        }
    }

    private static AuctionRenderApi acceptingApi() {
        return new AuctionRenderApi() {
            @Override
            public AuctionResult auction(AuctionRequest request) {
                return new AuctionResult(
                        "auction-1",
                        List.of(),
                        URI.create("https://ssp.test/render")
                );
            }

            @Override
            public RenderAcceptance completeRender(RenderCompleted render) {
                return RenderAcceptance.ACCEPTED;
            }
        };
    }

    private static void waitUntilDraining(HttpClient client, URI base) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            HttpResponse<Void> readiness = client.send(
                    HttpRequest.newBuilder(base.resolve("/health/ready")).GET().build(),
                    HttpResponse.BodyHandlers.discarding()
            );
            if (readiness.statusCode() == 503) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("server did not enter draining state");
    }

    private static HttpResponse<String> sendRender(HttpClient client, URI url) throws Exception {
        return client.send(
                post(url, "{\"renderProof\":\"proof-1\"}"),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private static HttpResponse<String> sendAuction(HttpClient client, URI url) throws Exception {
        return client.send(
                post(url, AUCTION_JSON),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private static void assertStatusAndCode(
            HttpResponse<String> response,
            int status,
            String code
    ) {
        assertEquals(status, response.statusCode());
        assertTrue(response.body().contains("\"code\":\"" + code + "\""));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static HttpRequest post(URI uri, String body) {
        return HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }
}
