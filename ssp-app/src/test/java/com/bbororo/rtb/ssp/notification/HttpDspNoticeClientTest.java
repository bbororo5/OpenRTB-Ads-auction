package com.bbororo.rtb.ssp.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bbororo.rtb.ssp.contract.SspMessages.DeliveryOutcome;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HttpDspNoticeClientTest {

    @Test
    void mapsHttpResultsToDeliveryPolicy() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        addStatus(server, "/accepted", 204);
        addStatus(server, "/request-timeout", 408);
        addStatus(server, "/rate-limited", 429);
        addStatus(server, "/server-failure", 503);
        addStatus(server, "/redirect", 302);
        addStatus(server, "/invalid", 400);
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            DspNoticeClient client = new HttpDspNoticeClient(HttpClient.newHttpClient());

            Duration timeout = Duration.ofSeconds(1);
            assertEquals(DeliveryOutcome.DELIVERED, client.send(base.resolve("/accepted"), timeout));
            assertEquals(DeliveryOutcome.RETRY, client.send(base.resolve("/request-timeout"), timeout));
            assertEquals(DeliveryOutcome.RETRY, client.send(base.resolve("/rate-limited"), timeout));
            assertEquals(DeliveryOutcome.RETRY, client.send(base.resolve("/server-failure"), timeout));
            assertEquals(DeliveryOutcome.UNDELIVERED, client.send(base.resolve("/redirect"), timeout));
            assertEquals(DeliveryOutcome.UNDELIVERED, client.send(base.resolve("/invalid"), timeout));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void treatsANetworkTimeoutAsRetryable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                exchange.sendResponseHeaders(204, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            URI url = URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/slow"
            );
            DspNoticeClient client = new HttpDspNoticeClient(HttpClient.newHttpClient());

            assertEquals(
                    DeliveryOutcome.RETRY,
                    client.send(url, Duration.ofMillis(20))
            );
        } finally {
            server.stop(0);
        }
    }

    private static void addStatus(HttpServer server, String path, int status) {
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
    }
}
