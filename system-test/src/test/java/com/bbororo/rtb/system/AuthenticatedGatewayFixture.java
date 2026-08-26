package com.bbororo.rtb.system;

import com.bbororo.rtb.dsp.openrtb.ArmeriaDspOpenRtbServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** 시스템 시험에서 게이트웨이의 인증 헤더 부여 경계만 재현한다. */
final class AuthenticatedGatewayFixture implements AutoCloseable {

    private final HttpServer server;
    private final HttpClient client;
    private final AtomicReference<URI> dspBaseUri = new AtomicReference<>();

    AuthenticatedGatewayFixture() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create test gateway", exception);
        }
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::forward);
        server.start();
    }

    URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    URI endpoint(String path) {
        return baseUri().resolve(path.startsWith("/") ? path.substring(1) : path);
    }

    void routeTo(URI targetBaseUri) {
        dspBaseUri.set(Objects.requireNonNull(targetBaseUri));
    }

    private void forward(HttpExchange exchange) throws IOException {
        URI targetBase = dspBaseUri.get();
        if (targetBase == null) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }
        String pathAndQuery = exchange.getRequestURI().getRawPath();
        if (exchange.getRequestURI().getRawQuery() != null) {
            pathAndQuery += "?" + exchange.getRequestURI().getRawQuery();
        }
        HttpRequest.BodyPublisher body = "GET".equals(exchange.getRequestMethod())
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes());
        HttpRequest.Builder request = HttpRequest.newBuilder(targetBase.resolve(pathAndQuery))
                .timeout(Duration.ofSeconds(2))
                .header(ArmeriaDspOpenRtbServer.AUTHENTICATED_SSP_HEADER, "ssp-system-test")
                .method(exchange.getRequestMethod(), body);
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (forwardable(name)) {
                values.forEach(value -> request.header(name, value));
            }
        });
        try {
            HttpResponse<byte[]> response = client.send(
                    request.build(), HttpResponse.BodyHandlers.ofByteArray());
            response.headers().firstValue("Content-Type")
                    .ifPresent(value -> exchange.getResponseHeaders().set("Content-Type", value));
            byte[] responseBody = response.body();
            exchange.sendResponseHeaders(
                    response.statusCode(), responseBody.length == 0 ? -1 : responseBody.length);
            if (responseBody.length > 0) {
                exchange.getResponseBody().write(responseBody);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            exchange.sendResponseHeaders(503, -1);
        } catch (IOException exception) {
            exchange.sendResponseHeaders(503, -1);
        } finally {
            exchange.close();
        }
    }

    private static boolean forwardable(String name) {
        return !"Host".equalsIgnoreCase(name)
                && !"Content-Length".equalsIgnoreCase(name)
                && !"Connection".equalsIgnoreCase(name)
                && !"Upgrade".equalsIgnoreCase(name)
                && !"HTTP2-Settings".equalsIgnoreCase(name)
                && !"Transfer-Encoding".equalsIgnoreCase(name)
                && !ArmeriaDspOpenRtbServer.AUTHENTICATED_SSP_HEADER.equalsIgnoreCase(name);
    }

    @Override
    public void close() {
        server.stop(0);
        client.close();
    }
}
