package com.bbororo.rtb.ssp.trust;

/**
 * 지역에 복제된 공급자 설정으로 만든 불변 신뢰 스냅숏이다.
 *
 * <p>경매 요청은 이 포트만 읽으며, 설정 원본이나 지역 PostgreSQL을 요청마다 조회하지 않는다.</p>
 */
public interface ProviderTrustSnapshot {

    long version();

    boolean permits(String providerId, String keyId);
}
