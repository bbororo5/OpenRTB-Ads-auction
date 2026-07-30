package com.bbororo.rtb.ssp.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionResult;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderAcceptance;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderCompleted;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProviderHttpServerTest {

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
            String auctionJson = """
                    {"providerId":"provider-1","providerKeyId":"key-1",
                     "providerRequestId":"request-1","tmaxMillis":50,
                     "slots":[{"impId":"imp-1","floorCpmKrw":1000}]}
                    """;
            HttpResponse<String> auction = client.send(
                    post(base.resolve("/publisher/auction"), auctionJson),
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

    private static HttpResponse<String> sendRender(HttpClient client, URI url) throws Exception {
        return client.send(
                post(url, "{\"renderProof\":\"proof-1\"}"),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private static HttpRequest post(URI uri, String body) {
        return HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }
}
