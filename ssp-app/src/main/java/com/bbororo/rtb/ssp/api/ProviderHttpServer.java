package com.bbororo.rtb.ssp.api;

import com.bbororo.rtb.ssp.auction.AuctionDeadlineExceededException;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderAcceptance;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderCompleted;
import com.bbororo.rtb.ssp.deduplication.AuctionDeduplicationCapacityException;
import com.bbororo.rtb.ssp.deduplication.ChangedAuctionRequestException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** 공급자 경매와 렌더링 완료를 받는 Java 21 HTTP 진입 어댑터다. */
public final class ProviderHttpServer implements AutoCloseable {

    private static final String JSON = "application/json";

    private final AuctionRenderApi api;
    private final ProviderApiJsonCodec codec;
    private final Clock clock;
    private final ProviderHttpLimits limits;
    private final Semaphore capacity;
    private final HttpServer server;
    private final ExecutorService executor;

    public ProviderHttpServer(
            InetSocketAddress address,
            AuctionRenderApi api,
            ProviderApiJsonCodec codec,
            Clock clock
    ) {
        this(address, api, codec, clock, ProviderHttpLimits.defaults());
    }

    public ProviderHttpServer(
            InetSocketAddress address,
            AuctionRenderApi api,
            ProviderApiJsonCodec codec,
            Clock clock,
            ProviderHttpLimits limits
    ) {
        this.api = Objects.requireNonNull(api);
        this.codec = Objects.requireNonNull(codec);
        this.clock = Objects.requireNonNull(clock);
        this.limits = Objects.requireNonNull(limits);
        this.capacity = new Semaphore(limits.maxInFlight());
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
        if (!acceptRequest(exchange, limits.maxAuctionRequestBytes())) {
            return;
        }
        if (!capacity.tryAcquire()) {
            sendError(exchange, 503, "SERVER_OVERLOADED");
            return;
        }
        try {
            var request = codec.decodeAuctionRequest(
                    readBody(exchange, limits.maxAuctionRequestBytes())
            );
            send(exchange, 200, codec.encodeAuctionResult(api.auction(request)));
        } catch (AuctionRejectedException exception) {
            send(exchange, 403, codec.encodeError(exception.getMessage()));
        } catch (RequestBodyTooLargeException exception) {
            sendError(exchange, 413, "REQUEST_TOO_LARGE");
        } catch (IllegalArgumentException exception) {
            sendError(exchange, 400, "INVALID_REQUEST");
        } catch (RuntimeException exception) {
            sendAuctionFailure(exchange, exception);
        } finally {
            capacity.release();
        }
    }

    private void handleRender(HttpExchange exchange) throws IOException {
        if (!acceptRequest(exchange, limits.maxRenderRequestBytes())) {
            return;
        }
        if (!capacity.tryAcquire()) {
            sendError(exchange, 503, "SERVER_OVERLOADED");
            return;
        }
        try {
            RenderAcceptance acceptance = api.completeRender(new RenderCompleted(
                    codec.decodeRenderProof(readBody(exchange, limits.maxRenderRequestBytes())),
                    clock.instant()
            ));
            switch (acceptance) {
                case ACCEPTED, DUPLICATE -> send(exchange, 204, new byte[0]);
                case REJECTED -> sendError(exchange, 400, "INVALID_RENDER_PROOF");
                case RETRY_LATER -> sendError(exchange, 503, "RETRY_LATER");
            }
        } catch (RequestBodyTooLargeException exception) {
            sendError(exchange, 413, "REQUEST_TOO_LARGE");
        } catch (IllegalArgumentException exception) {
            sendError(exchange, 400, "INVALID_REQUEST");
        } catch (RuntimeException exception) {
            sendError(exchange, 500, "INTERNAL_ERROR");
        } finally {
            capacity.release();
        }
    }

    private boolean acceptRequest(HttpExchange exchange, int maximumBodyBytes) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            send(exchange, 405, new byte[0]);
            return false;
        }
        if (!isJson(exchange)) {
            sendError(exchange, 415, "UNSUPPORTED_MEDIA_TYPE");
            return false;
        }
        long declaredLength;
        try {
            declaredLength = declaredContentLength(exchange);
        } catch (IllegalArgumentException exception) {
            sendError(exchange, 400, "INVALID_REQUEST");
            return false;
        }
        if (declaredLength > maximumBodyBytes) {
            sendError(exchange, 413, "REQUEST_TOO_LARGE");
            return false;
        }
        return true;
    }

    private static boolean isJson(HttpExchange exchange) {
        return exchange.getRequestHeaders()
                .getFirst("Content-Type") != null
                && exchange.getRequestHeaders()
                .getFirst("Content-Type")
                .split(";", 2)[0]
                .trim()
                .toLowerCase(Locale.ROOT)
                .equals(JSON);
    }

    private static long declaredContentLength(HttpExchange exchange) {
        String value = exchange.getRequestHeaders().getFirst("Content-Length");
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid Content-Length", exception);
        }
    }

    private static byte[] readBody(HttpExchange exchange, int maximumBytes) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            byte[] body = input.readNBytes(maximumBytes + 1);
            if (body.length > maximumBytes) {
                throw new RequestBodyTooLargeException();
            }
            return body;
        }
    }

    private void sendAuctionFailure(HttpExchange exchange, RuntimeException failure) throws IOException {
        Throwable cause = unwrap(failure);
        if (cause instanceof ChangedAuctionRequestException) {
            sendError(exchange, 409, "AUCTION_REQUEST_CONFLICT");
            return;
        }
        if (cause instanceof AuctionDeduplicationCapacityException) {
            sendError(exchange, 503, "SERVER_OVERLOADED");
            return;
        }
        if (cause instanceof AuctionDeadlineExceededException) {
            sendError(exchange, 504, "AUCTION_DEADLINE_EXCEEDED");
            return;
        }
        sendError(exchange, 500, "INTERNAL_ERROR");
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void sendError(HttpExchange exchange, int status, String code) throws IOException {
        send(exchange, status, codec.encodeError(code));
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

    private static final class RequestBodyTooLargeException extends RuntimeException {
    }
}
