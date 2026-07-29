package com.bbororo.rtb.ssp.api;

import com.bbororo.rtb.ssp.contract.SspMessages.RenderAcceptance;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderCompleted;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 공급자 경매와 렌더링 완료를 받는 Java 21 HTTP 진입 어댑터다. */
public final class ProviderHttpServer implements AutoCloseable {

    private static final String JSON = "application/json";

    private final AuctionRenderApi api;
    private final ProviderApiJsonCodec codec;
    private final Clock clock;
    private final HttpServer server;
    private final ExecutorService executor;

    public ProviderHttpServer(
            InetSocketAddress address,
            AuctionRenderApi api,
            ProviderApiJsonCodec codec,
            Clock clock
    ) {
        this.api = Objects.requireNonNull(api);
        this.codec = Objects.requireNonNull(codec);
        this.clock = Objects.requireNonNull(clock);
        try {
            server = HttpServer.create(Objects.requireNonNull(address), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create provider HTTP server", exception);
        }
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/publisher/auction", this::handleAuction);
        server.createContext("/publisher/render", this::handleRender);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handleAuction(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, new byte[0]);
            return;
        }
        try {
            var request = codec.decodeAuctionRequest(exchange.getRequestBody().readAllBytes());
            send(exchange, 200, codec.encodeAuctionResult(api.auction(request)));
        } catch (AuctionRejectedException exception) {
            send(exchange, 403, codec.encodeError(exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            send(exchange, 400, codec.encodeError("INVALID_REQUEST"));
        } catch (RuntimeException exception) {
            send(exchange, 500, codec.encodeError("INTERNAL_ERROR"));
        }
    }

    private void handleRender(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, new byte[0]);
            return;
        }
        try {
            RenderAcceptance acceptance = api.completeRender(new RenderCompleted(
                    codec.decodeRenderProof(exchange.getRequestBody().readAllBytes()),
                    clock.instant()
            ));
            switch (acceptance) {
                case ACCEPTED, DUPLICATE -> send(exchange, 204, new byte[0]);
                case REJECTED -> send(exchange, 400, codec.encodeError("INVALID_RENDER_PROOF"));
                case RETRY_LATER -> send(exchange, 503, codec.encodeError("RETRY_LATER"));
            }
        } catch (IllegalArgumentException exception) {
            send(exchange, 400, codec.encodeError("INVALID_REQUEST"));
        } catch (RuntimeException exception) {
            send(exchange, 500, codec.encodeError("INTERNAL_ERROR"));
        }
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        if (body.length > 0) {
            exchange.getResponseHeaders().set("Content-Type", JSON);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        } else {
            exchange.sendResponseHeaders(status, -1);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }
}
