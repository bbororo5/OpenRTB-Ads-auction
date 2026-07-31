package com.bbororo.rtb.ssp.api;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** 공급자 HTTP 경계의 저비용 운영 지표를 Prometheus 형식으로 노출한다. */
public final class ProviderHttpMetrics {

    private final LongAdder inFlight = new LongAdder();
    private final Map<ResponseKey, LongAdder> responses = new ConcurrentHashMap<>();
    private final Map<RejectionKey, LongAdder> rejections = new ConcurrentHashMap<>();

    public void requestStarted() {
        inFlight.increment();
    }

    public void requestFinished(String route, int status) {
        inFlight.decrement();
        responses.computeIfAbsent(new ResponseKey(route, status), ignored -> new LongAdder()).increment();
    }

    public void requestRejected(String route, int status, String reason) {
        responses.computeIfAbsent(new ResponseKey(route, status), ignored -> new LongAdder()).increment();
        rejections.computeIfAbsent(new RejectionKey(route, reason), ignored -> new LongAdder()).increment();
    }

    public byte[] prometheus() {
        StringBuilder output = new StringBuilder();
        output.append("# TYPE ssp_provider_http_requests_in_flight gauge\n")
                .append("ssp_provider_http_requests_in_flight ")
                .append(inFlight.sum())
                .append('\n');
        output.append("# TYPE ssp_provider_http_responses_total counter\n");
        responses.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().route() + entry.getKey().status()))
                .forEach(entry -> output.append("ssp_provider_http_responses_total{route=\"")
                        .append(entry.getKey().route())
                        .append("\",status=\"")
                        .append(entry.getKey().status())
                        .append("\"} ")
                        .append(entry.getValue().sum())
                        .append('\n'));
        output.append("# TYPE ssp_provider_http_rejections_total counter\n");
        rejections.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().route() + entry.getKey().reason()))
                .forEach(entry -> output.append("ssp_provider_http_rejections_total{route=\"")
                        .append(entry.getKey().route())
                        .append("\",reason=\"")
                        .append(entry.getKey().reason())
                        .append("\"} ")
                        .append(entry.getValue().sum())
                        .append('\n'));
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private record ResponseKey(String route, int status) {
    }

    private record RejectionKey(String route, String reason) {
    }
}
