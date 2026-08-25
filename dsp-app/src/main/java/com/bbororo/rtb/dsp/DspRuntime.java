package com.bbororo.rtb.dsp;

import com.bbororo.rtb.dsp.openrtb.ArmeriaDspOpenRtbServer;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** 조립된 DSP HTTP 런타임의 시작·대기·종료 순서를 소유한다. */
public final class DspRuntime implements AutoCloseable {

    private final ArmeriaDspOpenRtbServer server;
    private final List<Service> services;
    private final List<AutoCloseable> resources;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private boolean started;
    private boolean closed;

    DspRuntime(ArmeriaDspOpenRtbServer server) {
        this(server, List.of(), List.of());
    }

    DspRuntime(
            ArmeriaDspOpenRtbServer server,
            List<Service> services,
            List<AutoCloseable> resources
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.services = List.copyOf(Objects.requireNonNull(services, "services"));
        this.resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("DSP runtime is already closed");
        }
        if (started) {
            throw new IllegalStateException("DSP runtime is already started");
        }
        try {
            for (Service service : services) {
                service.start().run();
            }
            server.start();
            started = true;
        } catch (RuntimeException failure) {
            try {
                close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
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
        RuntimeException failure = null;
        failure = close(server, failure);
        var reverseServices = new ArrayList<>(services);
        Collections.reverse(reverseServices);
        for (Service service : reverseServices) {
            failure = close(service.closeable(), failure);
        }
        var reverseResources = new ArrayList<>(resources);
        Collections.reverse(reverseResources);
        for (AutoCloseable resource : reverseResources) {
            failure = close(resource, failure);
        }
        stopped.countDown();
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException close(
            AutoCloseable resource,
            RuntimeException previousFailure
    ) {
        try {
            resource.close();
            return previousFailure;
        } catch (Exception exception) {
            RuntimeException failure = exception instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new IllegalStateException("Could not close DSP runtime resource", exception);
            if (previousFailure == null) {
                return failure;
            }
            previousFailure.addSuppressed(failure);
            return previousFailure;
        }
    }

    record Service(Runnable start, AutoCloseable closeable) {
        Service {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(closeable, "closeable");
        }
    }
}
