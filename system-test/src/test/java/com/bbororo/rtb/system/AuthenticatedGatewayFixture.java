package com.bbororo.rtb.system;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 배포용 Node 게이트웨이 코드를 별도 프로세스로 실행한다. HTTP 동작을 재구현하지 않는다. */
final class AuthenticatedGatewayFixture implements AutoCloseable {
    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
    private Process process;
    private BufferedReader replies;
    private BufferedWriter commands;
    private URI baseUri;

    AuthenticatedGatewayFixture() {
        try {
            process = new ProcessBuilder(
                    System.getProperty("stage8c.node", "node"),
                    System.getProperty("stage8c.gateway-script"))
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            replies = process.inputReader(StandardCharsets.UTF_8);
            commands = process.outputWriter(StandardCharsets.UTF_8);
            baseUri = URI.create(awaitReply());
        } catch (Exception exception) {
            close();
            throw new IllegalStateException("Could not start deployed Node gateway fixture", exception);
        }
    }

    URI baseUri() { return baseUri; }

    URI endpoint(String path) {
        return baseUri.resolve(path.startsWith("/") ? path.substring(1) : path);
    }

    void routeTo(URI targetBaseUri) {
        try {
            commands.write(targetBaseUri.toString());
            commands.newLine();
            commands.flush();
            if (!"configured".equals(awaitReply())) {
                throw new IllegalStateException("Unexpected gateway configuration reply");
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not route Node gateway", exception);
        }
    }

    private String awaitReply() throws Exception {
        var reply = io.submit(replies::readLine);
        try {
            String line = reply.get(10, TimeUnit.SECONDS);
            if (line == null) throw new IllegalStateException("Node gateway exited before replying");
            return line;
        } finally {
            reply.cancel(true);
        }
    }

    @Override
    public void close() {
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        io.shutdownNow();
    }
}
