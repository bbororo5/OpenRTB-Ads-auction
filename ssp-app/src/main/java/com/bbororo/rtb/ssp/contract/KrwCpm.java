package com.bbororo.rtb.ssp.contract;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 외부 KRW CPM과 내부 고정소수점 값을 변환한다.
 *
 * <p>내부 값 1은 0.001 KRW CPM이다. 따라서 소수 셋째 자리까지 반올림 없이 보존한다.</p>
 */
public final class KrwCpm {

    private static final int SCALE = 3;

    private KrwCpm() {
    }

    public static long toMilliKrw(BigDecimal cpmKrw) {
        Objects.requireNonNull(cpmKrw, "cpmKrw");
        BigDecimal normalized = cpmKrw.stripTrailingZeros();
        if (normalized.signum() < 0 || normalized.scale() > SCALE) {
            throw new IllegalArgumentException("KRW CPM must be non-negative with at most three decimal places");
        }
        try {
            return cpmKrw.movePointRight(SCALE).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("KRW CPM is outside the supported range", exception);
        }
    }

    public static BigDecimal fromMilliKrw(long cpmMilliKrw) {
        if (cpmMilliKrw < 0) {
            throw new IllegalArgumentException("cpmMilliKrw must not be negative");
        }
        return BigDecimal.valueOf(cpmMilliKrw, SCALE);
    }
}
