package com.bbororo.rtb.system;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 기존 연결 종료와 신규 연결 거절을 제어하는 PostgreSQL TCP 장애 주입기다. */
final class JdbcFaultProxy implements AutoCloseable {

    private final String databasePath;
    private final String targetHost;
    private final int targetPort;
    private final ServerSocket listener;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean connectionsAllowed = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean();

    private JdbcFaultProxy(String targetHost, int targetPort, String databasePath) {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.databasePath = databasePath;
        try {
            listener = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        } catch (IOException failure) {
            throw new IllegalStateException("Could not create JDBC fault proxy", failure);
        }
        executor.submit(this::acceptConnections);
    }

    static JdbcFaultProxy fromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("A PostgreSQL JDBC URL is required");
        }
        URI target = URI.create(jdbcUrl.substring("jdbc:".length()));
        return new JdbcFaultProxy(
                target.getHost(),
                target.getPort() < 0 ? 5432 : target.getPort(),
                target.getRawPath() + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery()));
    }

    String jdbcUrl() {
        return "jdbc:postgresql://127.0.0.1:" + listener.getLocalPort() + databasePath;
    }

    void failConnections() {
        connectionsAllowed.set(false);
        openSockets.forEach(JdbcFaultProxy::closeQuietly);
        openSockets.clear();
    }

    void restoreConnections() {
        connectionsAllowed.set(true);
    }

    private void acceptConnections() {
        while (!closed.get()) {
            try {
                Socket client = listener.accept();
                if (!connectionsAllowed.get()) {
                    closeQuietly(client);
                    continue;
                }
                Socket target = new Socket(targetHost, targetPort);
                openSockets.add(client);
                openSockets.add(target);
                executor.submit(() -> pipe(client, target));
                executor.submit(() -> pipe(target, client));
            } catch (IOException failure) {
                if (!closed.get()) {
                    throw new IllegalStateException("JDBC fault proxy accept failed", failure);
                }
            }
        }
    }

    private void pipe(Socket source, Socket target) {
        try {
            source.getInputStream().transferTo(target.getOutputStream());
        } catch (IOException ignored) {
            // 연결 차단은 의도된 장애 입력이다.
        } finally {
            closePair(source, target);
        }
    }

    private void closePair(Socket first, Socket second) {
        openSockets.remove(first);
        openSockets.remove(second);
        closeQuietly(first);
        closeQuietly(second);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 이미 닫힌 장애 주입 연결이다.
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            listener.close();
        } catch (IOException ignored) {
            // 종료 중에는 listener가 이미 닫혀도 된다.
        }
        openSockets.forEach(JdbcFaultProxy::closeQuietly);
        openSockets.clear();
        executor.close();
    }
}
