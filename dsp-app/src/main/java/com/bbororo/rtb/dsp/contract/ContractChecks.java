package com.bbororo.rtb.dsp.contract;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** DSP 내부 메시지가 경계를 넘기 전에 지켜야 할 공통 형식 조건이다. */
public final class ContractChecks {

    private ContractChecks() {
    }

    public static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static long requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public static long requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    public static Instant requireAfter(Instant earlier, Instant later, String name) {
        Objects.requireNonNull(earlier, "earlier");
        Objects.requireNonNull(later, name);
        if (!later.isAfter(earlier)) {
            throw new IllegalArgumentException(name + " must be after " + earlier);
        }
        return later;
    }

    public static <T> List<T> immutableList(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " must not contain null");
        }
        return List.copyOf(values);
    }
}
