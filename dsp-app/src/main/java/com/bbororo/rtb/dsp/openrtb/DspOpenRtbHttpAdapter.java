package com.bbororo.rtb.dsp.openrtb;

import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuthenticatedBidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidResponse;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoContent;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuctionNotice;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoticeHttpResult;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoticeKind;
import com.bbororo.rtb.dsp.contract.AuctionDeadline;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 인증 게이트웨이 뒤에서 OpenRTB 입찰 HTTP 의미를 DSP 컴포넌트 호출로 변환한다. */
public final class DspOpenRtbHttpAdapter {

    public static final String OPENRTB_VERSION = "2.6";
    public static final String VERSION_HEADER = "x-openrtb-version";
    private static final String JSON = "application/json";

    private final DspOpenRtbApi api;
    private final DspOpenRtb26JsonCodec codec;
    private final LongSupplier monotonicNanos;

    public DspOpenRtbHttpAdapter(DspOpenRtbApi api) {
        this(api, new DspOpenRtb26JsonCodec(), System::nanoTime);
    }

    public DspOpenRtbHttpAdapter(DspOpenRtbApi api, LongSupplier monotonicNanos) {
        this(api, new DspOpenRtb26JsonCodec(), monotonicNanos);
    }

    DspOpenRtbHttpAdapter(
            DspOpenRtbApi api,
            DspOpenRtb26JsonCodec codec
    ) {
        this(api, codec, System::nanoTime);
    }

    DspOpenRtbHttpAdapter(
            DspOpenRtbApi api,
            DspOpenRtb26JsonCodec codec,
            LongSupplier monotonicNanos
    ) {
        this.api = Objects.requireNonNull(api);
        this.codec = Objects.requireNonNull(codec);
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos);
    }

    public Response handleBid(Request request) {
        Objects.requireNonNull(request, "request");
        if (!"POST".equalsIgnoreCase(request.method())
                || !isJson(request.contentType())
                || (request.openRtbVersion() != null
                && !OPENRTB_VERSION.equals(request.openRtbVersion()))) {
            return Response.noContent(400);
        }
        OpenRtbMessages.BidRequest bidRequest;
        try {
            bidRequest = codec.decodeBidRequest(request.body());
        } catch (IllegalArgumentException exception) {
            return Response.noContent(400);
        }
        try {
            var result = Objects.requireNonNull(api.handleBid(new AuthenticatedBidRequest(
                    request.authenticatedSspId(),
                    bidRequest,
                    request.receivedAt(),
                    AuctionDeadline.startAt(
                            bidRequest.tmaxMillis(),
                            request.receivedNanos(),
                            monotonicNanos
                    )
            )), "DspOpenRtbApi returned null");
            if (result == NoContent.INSTANCE) {
                return Response.noContent(204);
            }
            if (result instanceof BidResponse response) {
                return Response.json(200, codec.encodeBidResponse(response));
            }
            return Response.noContent(500);
        } catch (RuntimeException exception) {
            return Response.noContent(500);
        }
    }

    public CompletionStage<Response> handleNotice(NoticeRequest request) {
        Objects.requireNonNull(request, "request");
        if (!"GET".equalsIgnoreCase(request.method())) {
            return CompletableFuture.completedFuture(Response.noContent(405));
        }
        CompletionStage<NoticeHttpResult> processing;
        try {
            processing = Objects.requireNonNull(api.handleNotice(new AuctionNotice(
                    request.authenticatedSspId(),
                    request.kind(),
                    request.opaqueToken(),
                    request.receivedAt()
            )), "DspOpenRtbApi returned null");
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(Response.noContent(500));
        }
        return processing.handle((result, failure) -> {
            if (failure != null || result == null) {
                return Response.noContent(500);
            }
            return switch (result) {
                case ACCEPTED -> Response.noContent(204);
                case INVALID -> Response.noContent(400);
                case TEMPORARILY_UNAVAILABLE -> Response.noContent(503);
            };
        });
    }

    private static boolean isJson(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        return JSON.equals(contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT));
    }

    public record Request(
            String method,
            String contentType,
            String openRtbVersion,
            String authenticatedSspId,
            Instant receivedAt,
            long receivedNanos,
            byte[] body
    ) {
        public Request(
                String method,
                String contentType,
                String openRtbVersion,
                String authenticatedSspId,
                Instant receivedAt,
                byte[] body
        ) {
            this(
                    method,
                    contentType,
                    openRtbVersion,
                    authenticatedSspId,
                    receivedAt,
                    System.nanoTime(),
                    body
            );
        }

        public Request {
            if (method == null || method.isBlank()) {
                throw new IllegalArgumentException("method must not be blank");
            }
            if (authenticatedSspId == null || authenticatedSspId.isBlank()) {
                throw new IllegalArgumentException("authenticatedSspId must not be blank");
            }
            Objects.requireNonNull(receivedAt, "receivedAt");
            body = Objects.requireNonNull(body, "body").clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    public record NoticeRequest(
            String method,
            String authenticatedSspId,
            NoticeKind kind,
            String opaqueToken,
            Instant receivedAt
    ) {
        public NoticeRequest {
            if (method == null || method.isBlank()) {
                throw new IllegalArgumentException("method must not be blank");
            }
            if (authenticatedSspId == null || authenticatedSspId.isBlank()) {
                throw new IllegalArgumentException("authenticatedSspId must not be blank");
            }
            Objects.requireNonNull(kind, "kind");
            if (opaqueToken == null || opaqueToken.isBlank()) {
                throw new IllegalArgumentException("opaqueToken must not be blank");
            }
            Objects.requireNonNull(receivedAt, "receivedAt");
        }
    }

    public record Response(int statusCode, Map<String, String> headers, byte[] body) {
        public Response {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("statusCode must be an HTTP status");
            }
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
            body = Objects.requireNonNull(body, "body").clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        private static Response json(int statusCode, byte[] body) {
            return new Response(
                    statusCode,
                    Map.of("Content-Type", JSON, VERSION_HEADER, OPENRTB_VERSION),
                    body
            );
        }

        static Response noContent(int statusCode) {
            return new Response(
                    statusCode,
                    Map.of(VERSION_HEADER, OPENRTB_VERSION),
                    new byte[0]
            );
        }
    }
}
