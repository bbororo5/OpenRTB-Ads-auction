package com.bbororo.rtb.ssp.dspbid;

import com.bbororo.rtb.ssp.contract.SspMessages.BidRequestBatch;
import com.bbororo.rtb.ssp.contract.SspMessages.BidResponses;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcomeKind;
import com.bbororo.rtb.ssp.dspbid.LimitedByteArrayBodyHandler.ResponseTooLargeException;
import com.bbororo.rtb.ssp.openrtb.OpenRtb26Codec;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** DSP 회사별 용량과 하위 기한 안에서 OpenRTB 요청을 병렬 실행한다. */
public final class HttpOpenRtbDspBidExecutor implements DspBidExecutor {

    private static final Duration RESULT_ASSEMBLY_BUDGET = Duration.ofMillis(1);

    private final OpenRtb26Codec codec;
    private final Map<String, DspBidChannel> channels;
    private final Duration bidTimeout;
    private final LimitedByteArrayBodyHandler bodyHandler;

    public HttpOpenRtbDspBidExecutor(
            OpenRtb26Codec codec,
            Map<String, DspBidChannel> channels,
            Duration bidTimeout,
            int maxResponseBytes
    ) {
        this.codec = Objects.requireNonNull(codec);
        this.channels = Map.copyOf(Objects.requireNonNull(channels));
        if (this.channels.isEmpty()) {
            throw new IllegalArgumentException("At least one DSP bid channel is required");
        }
        if (bidTimeout == null || bidTimeout.isZero() || bidTimeout.isNegative()) {
            throw new IllegalArgumentException("bidTimeout must be positive");
        }
        this.bidTimeout = bidTimeout;
        this.bodyHandler = new LimitedByteArrayBodyHandler(maxResponseBytes);
    }

    @Override
    public BidResponses requestBids(BidRequestBatch batch) {
        Objects.requireNonNull(batch);
        int advertisedTmaxMillis = advertisedTmaxMillis(batch);
        if (advertisedTmaxMillis == 0) {
            return new BidResponses(batch.dspIds().stream()
                    .map(dspId -> outcome(dspId, DspCallOutcomeKind.TIMEOUT))
                    .toList());
        }
        byte[] requestBody = codec.encodeBidRequest(batch, advertisedTmaxMillis);
        Map<String, PendingCall> calls = new LinkedHashMap<>();
        for (String dspId : batch.dspIds()) {
            calls.put(dspId, startCall(dspId, batch, requestBody));
        }

        awaitCalls(batch, calls);
        List<DspCallOutcome> outcomes = batch.dspIds().stream()
                .map(dspId -> completedOutcome(dspId, calls.get(dspId)))
                .toList();
        return new BidResponses(outcomes);
    }

    private PendingCall startCall(String dspId, BidRequestBatch batch, byte[] requestBody) {
        DspBidChannel channel = channels.get(dspId);
        if (channel == null) {
            return PendingCall.completed(outcome(dspId, DspCallOutcomeKind.ERROR));
        }
        Duration callBudget = callBudget(batch);
        if (callBudget.isZero()) {
            return PendingCall.completed(outcome(dspId, DspCallOutcomeKind.TIMEOUT));
        }
        if (!channel.tryAcquire()) {
            return PendingCall.completed(outcome(dspId, DspCallOutcomeKind.ERROR));
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(channel.endpoint())
                    .timeout(callBudget)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("x-openrtb-version", "2.6")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .build();
            CompletableFuture<HttpResponse<byte[]>> transport = channel.send(request, bodyHandler);
            CompletableFuture<DspCallOutcome> result = transport
                    .handle((response, failure) -> failure == null
                            ? toOutcome(dspId, batch, response)
                            : failureOutcome(dspId, failure))
                    .whenComplete((ignored, failure) -> channel.release());
            return new PendingCall(transport, result);
        } catch (RuntimeException exception) {
            channel.release();
            return PendingCall.completed(outcome(dspId, DspCallOutcomeKind.ERROR));
        }
    }

    private void awaitCalls(BidRequestBatch batch, Map<String, PendingCall> calls) {
        CompletableFuture<?>[] results = calls.values().stream()
                .map(PendingCall::result)
                .toArray(CompletableFuture[]::new);
        Duration remaining = remainingBeforeAssembly(batch);
        if (remaining.isZero()) {
            calls.values().forEach(PendingCall::cancel);
            return;
        }
        try {
            CompletableFuture.allOf(results)
                    .get(remaining.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            calls.values().forEach(PendingCall::cancel);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            calls.values().forEach(PendingCall::cancel);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("DSP outcomes must contain their failures", exception);
        }
    }

    private DspCallOutcome completedOutcome(String dspId, PendingCall call) {
        if (!call.result().isDone()) {
            call.cancel();
            return outcome(dspId, DspCallOutcomeKind.TIMEOUT);
        }
        try {
            return call.result().join();
        } catch (CancellationException exception) {
            return outcome(dspId, DspCallOutcomeKind.TIMEOUT);
        }
    }

    private Duration callBudget(BidRequestBatch batch) {
        Duration remaining = remainingBeforeAssembly(batch);
        return remaining.compareTo(bidTimeout) < 0 ? remaining : bidTimeout;
    }

    private int advertisedTmaxMillis(BidRequestBatch batch) {
        long wholeMillis = callBudget(batch).toMillis();
        return wholeMillis == 0 ? 0 : Math.toIntExact(wholeMillis);
    }

    private static Duration remainingBeforeAssembly(BidRequestBatch batch) {
        Duration remaining = batch.deadline().remaining();
        return remaining.compareTo(RESULT_ASSEMBLY_BUDGET) <= 0
                ? Duration.ZERO
                : remaining.minus(RESULT_ASSEMBLY_BUDGET);
    }

    private DspCallOutcome toOutcome(
            String dspId,
            BidRequestBatch batch,
            HttpResponse<byte[]> response
    ) {
        if (response.statusCode() == 204) {
            return outcome(dspId, DspCallOutcomeKind.NO_BID);
        }
        if (response.statusCode() != 200) {
            return outcome(dspId, DspCallOutcomeKind.ERROR);
        }
        if (!isJson(response) || response.body().length == 0) {
            return outcome(dspId, DspCallOutcomeKind.INVALID_BID);
        }
        return codec.decodeBidResponse(dspId, batch, response.body());
    }

    private static boolean isJson(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type")
                .map(value -> value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
                .filter("application/json"::equals)
                .isPresent();
    }

    private static DspCallOutcome failureOutcome(String dspId, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof HttpTimeoutException || cause instanceof CancellationException) {
            return outcome(dspId, DspCallOutcomeKind.TIMEOUT);
        }
        if (cause instanceof ResponseTooLargeException) {
            return outcome(dspId, DspCallOutcomeKind.INVALID_BID);
        }
        return outcome(dspId, DspCallOutcomeKind.ERROR);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static DspCallOutcome outcome(String dspId, DspCallOutcomeKind kind) {
        return new DspCallOutcome(dspId, kind, List.of());
    }

    private record PendingCall(
            CompletableFuture<HttpResponse<byte[]>> transport,
            CompletableFuture<DspCallOutcome> result
    ) {

        private static PendingCall completed(DspCallOutcome outcome) {
            return new PendingCall(null, CompletableFuture.completedFuture(outcome));
        }

        private void cancel() {
            if (transport != null && !transport.isDone()) {
                transport.cancel(true);
            }
        }
    }
}
