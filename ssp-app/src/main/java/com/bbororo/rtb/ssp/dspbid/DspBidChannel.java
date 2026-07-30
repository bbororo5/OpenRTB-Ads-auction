package com.bbororo.rtb.ssp.dspbid;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/** 한 DSP 회사에 귀속된 HTTP 연결 자원과 동시 호출 상한이다. */
public final class DspBidChannel {

    private final URI endpoint;
    private final HttpClient client;
    private final Semaphore capacity;

    public DspBidChannel(URI endpoint, HttpClient client, int maxInFlight) {
        if (endpoint == null
                || !endpoint.isAbsolute()
                || (!"http".equalsIgnoreCase(endpoint.getScheme())
                && !"https".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IllegalArgumentException("DSP endpoint must be an absolute HTTP URL");
        }
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }
        this.endpoint = endpoint;
        this.client = Objects.requireNonNull(client);
        this.capacity = new Semaphore(maxInFlight);
    }

    URI endpoint() {
        return endpoint;
    }

    boolean tryAcquire() {
        return capacity.tryAcquire();
    }

    void release() {
        capacity.release();
    }

    CompletableFuture<HttpResponse<byte[]>> send(
            HttpRequest request,
            HttpResponse.BodyHandler<byte[]> bodyHandler
    ) {
        return client.sendAsync(request, bodyHandler);
    }
}
