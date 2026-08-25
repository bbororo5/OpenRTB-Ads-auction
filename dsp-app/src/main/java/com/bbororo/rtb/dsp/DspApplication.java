package com.bbororo.rtb.dsp;

/** 운영 설정으로 DSP를 조립하고 프로세스 종료까지 유지하는 진입점이다. */
public final class DspApplication {

    private DspApplication() {
    }

    public static void main(String[] args) throws InterruptedException {
        try (DspRuntime runtime = DspRuntimeFactory.createFromEnvironment()) {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(runtime::close, "dsp-shutdown")
            );
            runtime.start();
            runtime.awaitShutdown();
        }
    }
}
