package com.bbororo.rtb.system;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("stage-8c-system")
class NodeGatewayTransportTest {
    @Test
    void defaultJavaClientCanReachUpstreamThroughDeployedNodeGateway() throws Exception {
        var received = new AtomicInteger();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/bid", exchange -> {
            exchange.getRequestBody().readAllBytes();
            received.incrementAndGet();
            byte[] body = exchange.getRequestHeaders().getFirst("x-authenticated-ssp-id")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        try (var gateway = new AuthenticatedGatewayFixture();
             var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            gateway.routeTo(URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()));
            for (int attempt = 0; attempt < 3; attempt++) {
                var request = HttpRequest.newBuilder(gateway.endpoint("/bid"))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .header("x-authenticated-ssp-id", "spoofed")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build();
                var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join();
                assertEquals(200, response.statusCode(), response.body());
                assertEquals("ssp-system-test", response.body());
            }
            assertEquals(3, received.get());
        } finally {
            upstream.stop(0);
        }
    }
}
