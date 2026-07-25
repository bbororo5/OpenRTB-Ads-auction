package com.bbororo.rtb.ssp.trust;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 경매 경로가 읽는 현재 공급자 신뢰 스냅숏이다.
 *
 * <p>새 버전은 완성된 불변 스냅숏 하나로만 교체한다. 따라서 경매 스레드는 락과 DB 조회 없이 이전 또는
 * 이후 버전 하나를 관찰하며, 정책과 키의 혼합 상태를 관찰하지 않는다.</p>
 */
public final class ProviderTrustSnapshotHolder implements ProviderTrustSnapshot {

    private final AtomicReference<ProviderTrustSnapshot> current;

    public ProviderTrustSnapshotHolder(ProviderTrustSnapshot initialSnapshot) {
        this.current = new AtomicReference<>(Objects.requireNonNull(initialSnapshot));
    }

    /** 후보가 더 새로운 완결 버전일 때만 현재 스냅숏을 교체한다. */
    public boolean replaceIfNewer(ProviderTrustSnapshot candidate) {
        Objects.requireNonNull(candidate);

        while (true) {
            ProviderTrustSnapshot observed = current.get();
            if (candidate.version() <= observed.version()) {
                return false;
            }
            if (current.compareAndSet(observed, candidate)) {
                return true;
            }
        }
    }

    @Override
    public long version() {
        return current.get().version();
    }

    @Override
    public boolean permits(String providerId, String keyId) {
        return current.get().permits(providerId, keyId);
    }

    @Override
    public boolean isActive(String providerId) {
        return current.get().isActive(providerId);
    }
}
