package com.bbororo.rtb.dsp.openrtb;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Armeria를 OpenRTB HTTP 어댑터 바깥에만 두는 DSP 전송 서버다. */
public final class ArmeriaDspOpenRtbServer implements AutoCloseable {

    /** 외부 요청에서 제거한 뒤 신뢰 경계의 게이트웨이만 다시 설정해야 하는 내부 헤더다. */
    public static final String AUTHENTICATED_SSP_HEADER = "x-authenticated-ssp-id";

    private final Server server;
    private final ThreadPoolExecutor bidExecutor;

    public ArmeriaDspOpenRtbServer(
            Settings settings,
            DspOpenRtbHttpAdapter adapter,
            Clock clock
    ) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(clock, "clock");

        bidExecutor = new ThreadPoolExecutor(
                settings.bidWorkers(),
                settings.bidWorkers(),
                0,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                namedThreads("dsp-bid-worker-"),
                new ThreadPoolExecutor.AbortPolicy()
        );
        bidExecutor.prestartAllCoreThreads();

        server = Server.builder()
                .http(settings.port())
                .maxRequestLength(settings.maxRequestBytes())
                .requestTimeout(settings.requestTimeout())
                .gracefulShutdownTimeout(
                        settings.gracefulQuietPeriod(),
                        settings.gracefulTimeout()
                )
                .service(settings.bidPath(), (ctx, request) -> serve(
                        ctx, request, adapter, clock))
                .build();
    }

    public void start() {
        server.start().join();
    }

    public int activePort() {
        return server.activeLocalPort();
    }

    private HttpResponse serve(
            ServiceRequestContext context,
            HttpRequest request,
            DspOpenRtbHttpAdapter adapter,
            Clock clock
    ) {
        Instant receivedAt = clock.instant();
        String authenticatedSspId = request.headers().get(AUTHENTICATED_SSP_HEADER);
        if (authenticatedSspId == null || authenticatedSspId.isBlank()) {
            return toArmeriaResponse(DspOpenRtbHttpAdapter.Response.noContent(401));
        }

        return HttpResponse.of(request.aggregate().thenCompose(aggregated -> {
            var inbound = new DspOpenRtbHttpAdapter.Request(
                    request.method().name(),
                    request.headers().get(HttpHeaderNames.CONTENT_TYPE),
                    request.headers().get(DspOpenRtbHttpAdapter.VERSION_HEADER),
                    authenticatedSspId,
                    receivedAt,
                    aggregated.content().array()
            );
            try {
                return CompletableFuture.supplyAsync(
                        () -> toArmeriaResponse(adapter.handleBid(inbound)),
                        bidExecutor
                );
            } catch (RejectedExecutionException rejected) {
                return CompletableFuture.completedFuture(toArmeriaResponse(
                        DspOpenRtbHttpAdapter.Response.noContent(503)
                ));
            }
        }));
    }

    private static HttpResponse toArmeriaResponse(DspOpenRtbHttpAdapter.Response response) {
        var headers = ResponseHeaders.builder(HttpStatus.valueOf(response.statusCode()));
        response.headers().forEach(headers::add);
        byte[] body = response.body();
        return body.length == 0
                ? HttpResponse.of(headers.build())
                : HttpResponse.of(headers.build(), HttpData.wrap(body));
    }

    private static ThreadFactory namedThreads(String prefix) {
        return Thread.ofPlatform().name(prefix, 0).factory();
    }

    @Override
    public void close() {
        server.stop().join();
        bidExecutor.shutdown();
        try {
            if (!bidExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                bidExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            bidExecutor.shutdownNow();
        }
    }

    public record Settings(
            int port,
            String bidPath,
            long maxRequestBytes,
            Duration requestTimeout,
            Duration gracefulQuietPeriod,
            Duration gracefulTimeout,
            int bidWorkers
    ) {
        public Settings {
            if (port < 0 || port > 65_535) {
                throw new IllegalArgumentException("port must be between 0 and 65535");
            }
            if (bidPath == null || !bidPath.startsWith("/")) {
                throw new IllegalArgumentException("bidPath must be absolute");
            }
            if (maxRequestBytes <= 0) {
                throw new IllegalArgumentException("maxRequestBytes must be positive");
            }
            requirePositive(requestTimeout, "requestTimeout");
            requireNonNegative(gracefulQuietPeriod, "gracefulQuietPeriod");
            requirePositive(gracefulTimeout, "gracefulTimeout");
            if (gracefulTimeout.compareTo(gracefulQuietPeriod) < 0) {
                throw new IllegalArgumentException(
                        "gracefulTimeout must not be shorter than gracefulQuietPeriod");
            }
            if (bidWorkers <= 0) {
                throw new IllegalArgumentException("bidWorkers must be positive");
            }
        }

        private static void requirePositive(Duration value, String name) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }

        private static void requireNonNegative(Duration value, String name) {
            if (value == null || value.isNegative()) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
        }
    }
}
