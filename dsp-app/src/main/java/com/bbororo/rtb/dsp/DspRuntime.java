package com.bbororo.rtb.dsp;

import com.bbororo.rtb.dsp.openrtb.ArmeriaDspOpenRtbServer;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/** 조립된 DSP HTTP 런타임의 시작·대기·종료 순서를 소유한다. */
public final class DspRuntime implements AutoCloseable {

    private final ArmeriaDspOpenRtbServer server;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private boolean started;
    private boolean closed;

    DspRuntime(ArmeriaDspOpenRtbServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("DSP runtime is already closed");
        }
        if (started) {
            throw new IllegalStateException("DSP runtime is already started");
        }
        try {
            server.start();
            started = true;
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public int activePort() {
        if (!started || closed) {
            throw new IllegalStateException("DSP runtime is not running");
        }
        return server.activePort();
    }

    public void awaitShutdown() throws InterruptedException {
        stopped.await();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            server.close();
        } finally {
            stopped.countDown();
        }
    }
}
