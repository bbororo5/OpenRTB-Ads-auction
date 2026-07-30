package com.bbororo.rtb.ssp;

/** SSP 런타임을 만들고 프로세스 종료까지 유지하는 실행 진입점이다. */
public final class SspApplication {

    private SspApplication() {
    }

    public static void main(String[] args) throws InterruptedException {
        try (SspRuntime runtime = SspRuntimeFactory.createFromEnvironment()) {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(runtime::close, "ssp-shutdown")
            );
            runtime.start();
            runtime.awaitShutdown();
        }
    }
}
