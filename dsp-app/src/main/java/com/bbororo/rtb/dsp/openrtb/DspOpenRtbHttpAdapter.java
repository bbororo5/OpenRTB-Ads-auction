package com.bbororo.rtb.dsp.openrtb;

import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuthenticatedBidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidResponse;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoContent;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 인증 게이트웨이 뒤에서 OpenRTB 입찰 HTTP 의미를 DSP 컴포넌트 호출로 변환한다. */
public final class DspOpenRtbHttpAdapter {

    public static final String OPENRTB_VERSION = "2.6";
    public static final String VERSION_HEADER = "x-openrtb-version";
    private static final String JSON = "application/json";

    private final DspOpenRtbApi api;
    private final DspOpenRtb26JsonCodec codec;

    public DspOpenRtbHttpAdapter(DspOpenRtbApi api) {
        this(api, new DspOpenRtb26JsonCodec());
    }

    DspOpenRtbHttpAdapter(DspOpenRtbApi api, DspOpenRtb26JsonCodec codec) {
        this.api = Objects.requireNonNull(api);
        this.codec = Objects.requireNonNull(codec);
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
                    request.authenticatedSspId(), bidRequest, request.receivedAt()
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
            byte[] body
    ) {
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

        private static Response noContent(int statusCode) {
            return new Response(
                    statusCode,
                    Map.of(VERSION_HEADER, OPENRTB_VERSION),
                    new byte[0]
            );
        }
    }
}
