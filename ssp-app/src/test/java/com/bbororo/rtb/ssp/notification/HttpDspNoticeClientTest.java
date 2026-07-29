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
        server.createContext("/accepted", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/temporary", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/invalid", exchange -> {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            DspNoticeClient client = new HttpDspNoticeClient(HttpClient.newHttpClient(), Duration.ofSeconds(1));

            assertEquals(DeliveryOutcome.DELIVERED, client.send(base.resolve("/accepted")));
            assertEquals(DeliveryOutcome.RETRY, client.send(base.resolve("/temporary")));
            assertEquals(DeliveryOutcome.UNDELIVERED, client.send(base.resolve("/invalid")));
        } finally {
            server.stop(0);
        }
    }
}
