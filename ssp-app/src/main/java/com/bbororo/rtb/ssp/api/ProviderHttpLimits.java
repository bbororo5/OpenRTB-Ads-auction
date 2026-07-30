package com.bbororo.rtb.ssp.api;

/** 공급자 HTTP 진입점이 애플리케이션에 허용하는 동시 처리와 본문 크기다. */
public record ProviderHttpLimits(
        int maxInFlight,
        int maxAuctionRequestBytes,
        int maxRenderRequestBytes
) {

    public ProviderHttpLimits {
        if (maxInFlight <= 0 || maxInFlight > 100_000) {
            throw new IllegalArgumentException("maxInFlight must be between 1 and 100000");
        }
        requireByteLimit(maxAuctionRequestBytes, "maxAuctionRequestBytes", 1_048_576);
        requireByteLimit(maxRenderRequestBytes, "maxRenderRequestBytes", 65_536);
    }

    public static ProviderHttpLimits defaults() {
        return new ProviderHttpLimits(128, 65_536, 8_192);
    }

    private static void requireByteLimit(int value, String name, int maximum) {
        if (value < 1_024 || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between 1024 and " + maximum
            );
        }
    }
}
