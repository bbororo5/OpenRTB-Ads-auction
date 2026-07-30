package com.bbororo.rtb.ssp.dspbid;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** 제한을 넘는 DSP 응답을 전부 메모리에 올리기 전에 중단한다. */
final class LimitedByteArrayBodyHandler implements HttpResponse.BodyHandler<byte[]> {

    private final int maxBytes;

    LimitedByteArrayBodyHandler(int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    @Override
    public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo responseInfo) {
        return new LimitedSubscriber(maxBytes);
    }

    static final class ResponseTooLargeException extends RuntimeException {

        ResponseTooLargeException(int maxBytes) {
            super("DSP response exceeds " + maxBytes + " bytes");
        }
    }

    private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final int maxBytes;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private int receivedBytes;

        private LimitedSubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            int incomingBytes = buffers.stream().mapToInt(ByteBuffer::remaining).sum();
            if (incomingBytes > maxBytes - receivedBytes) {
                subscription.cancel();
                body.completeExceptionally(new ResponseTooLargeException(maxBytes));
                return;
            }
            for (ByteBuffer buffer : buffers) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            receivedBytes += incomingBytes;
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }
}
