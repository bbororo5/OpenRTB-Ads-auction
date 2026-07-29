package com.bbororo.rtb.ssp.dspbid;

import com.bbororo.rtb.ssp.contract.SspMessages.BidRequestBatch;
import com.bbororo.rtb.ssp.contract.SspMessages.BidResponses;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcomeKind;
import com.bbororo.rtb.ssp.openrtb.OpenRtb26Codec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 같은 OpenRTB 요청을 DSP별 독립 HTTP 호출로 동시에 전송한다. */
public final class HttpOpenRtbDspBidExecutor implements DspBidExecutor {

    private final HttpClient client;
    private final OpenRtb26Codec codec;
    private final Map<String, URI> endpoints;

    public HttpOpenRtbDspBidExecutor(HttpClient client, OpenRtb26Codec codec, Map<String, URI> endpoints) {
        this.client = Objects.requireNonNull(client);
        this.codec = Objects.requireNonNull(codec);
        this.endpoints = Map.copyOf(endpoints);
    }

    @Override
    public BidResponses requestBids(BidRequestBatch batch) {
        Objects.requireNonNull(batch);
        byte[] requestBody = codec.encodeBidRequest(batch);
        Map<String, CompletableFuture<HttpResponse<byte[]>>> calls = new LinkedHashMap<>();

        for (String dspId : batch.dspIds()) {
            URI endpoint = endpoints.get(dspId);
            if (endpoint == null || batch.deadline().isExpired()) {
                continue;
            }
            Duration remaining = batch.deadline().remaining();
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(atLeastOneMillisecond(remaining))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .build();
            calls.put(dspId, client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()));
        }

        List<DspCallOutcome> outcomes = new ArrayList<>(batch.dspIds().size());
        for (String dspId : batch.dspIds()) {
            CompletableFuture<HttpResponse<byte[]>> call = calls.get(dspId);
            if (call == null) {
                outcomes.add(outcome(dspId, DspCallOutcomeKind.TIMEOUT));
                continue;
            }
            try {
                Duration remaining = batch.deadline().remaining();
                if (remaining.isZero()) {
                    call.cancel(true);
                    outcomes.add(outcome(dspId, DspCallOutcomeKind.TIMEOUT));
                    continue;
                }
                HttpResponse<byte[]> response = call.get(remaining.toNanos(), TimeUnit.NANOSECONDS);
                outcomes.add(toOutcome(dspId, batch.auctionId(), response));
            } catch (java.util.concurrent.TimeoutException exception) {
                call.cancel(true);
                outcomes.add(outcome(dspId, DspCallOutcomeKind.TIMEOUT));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                call.cancel(true);
                outcomes.add(outcome(dspId, DspCallOutcomeKind.ERROR));
            } catch (Exception exception) {
                outcomes.add(outcome(dspId, DspCallOutcomeKind.ERROR));
            }
        }
        return new BidResponses(outcomes);
    }

    private DspCallOutcome toOutcome(String dspId, String auctionId, HttpResponse<byte[]> response) {
        if (response.statusCode() == 204) {
            return outcome(dspId, DspCallOutcomeKind.NO_BID);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return outcome(dspId, DspCallOutcomeKind.ERROR);
        }
        return codec.decodeBidResponse(dspId, auctionId, response.body());
    }

    private static DspCallOutcome outcome(String dspId, DspCallOutcomeKind kind) {
        return new DspCallOutcome(dspId, kind, List.of());
    }

    private static Duration atLeastOneMillisecond(Duration duration) {
        return duration.compareTo(Duration.ofMillis(1)) < 0 ? Duration.ofMillis(1) : duration;
    }
}
