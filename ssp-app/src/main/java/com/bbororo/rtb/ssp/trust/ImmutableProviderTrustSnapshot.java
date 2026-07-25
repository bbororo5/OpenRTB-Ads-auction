package com.bbororo.rtb.ssp.trust;

import java.util.Map;
import java.util.Set;

/** PostgreSQL의 완결된 설정 버전을 메모리에 옮긴 불변 구현체다. */
public final class ImmutableProviderTrustSnapshot implements ProviderTrustSnapshot {

    private final long version;
    private final Map<String, ProviderPolicy> policies;

    public ImmutableProviderTrustSnapshot(long version, Map<String, ProviderPolicy> policies) {
        this.version = version;
        this.policies = Map.copyOf(policies);
    }

    @Override
    public long version() {
        return version;
    }

    @Override
    public boolean permits(String providerId, String keyId) {
        ProviderPolicy policy = policies.get(providerId);
        return policy != null && policy.active() && policy.activeKeyIds().contains(keyId);
    }

    @Override
    public boolean isActive(String providerId) {
        ProviderPolicy policy = policies.get(providerId);
        return policy != null && policy.active();
    }

    public record ProviderPolicy(boolean active, Set<String> activeKeyIds) {

        public ProviderPolicy {
            activeKeyIds = Set.copyOf(activeKeyIds);
        }
    }
}
