package com.bbororo.rtb.ssp.notification;

import com.bbororo.rtb.ssp.contract.SspMessages.DeliveryOutcome;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** OpenRTB 통지 URL을 일반 HTTP 요청으로 호출한다. */
public final class HttpDspNoticeClient implements DspNoticeClient {

    private final HttpClient client;
    private final Duration timeout;

    public HttpDspNoticeClient(HttpClient client, Duration timeout) {
        this.client = Objects.requireNonNull(client);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.timeout = timeout;
    }

    @Override
    public DeliveryOutcome send(URI noticeUrl) {
        Objects.requireNonNull(noticeUrl);
        HttpRequest request = HttpRequest.newBuilder(noticeUrl)
                .timeout(timeout)
                .GET()
                .build();
        try {
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) {
                return DeliveryOutcome.DELIVERED;
            }
            if (status == 408 || status == 429 || status >= 500) {
                return DeliveryOutcome.RETRY;
            }
            return DeliveryOutcome.UNDELIVERED;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return DeliveryOutcome.RETRY;
        } catch (Exception exception) {
            return DeliveryOutcome.RETRY;
        }
    }
}
