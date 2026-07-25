package com.bbororo.rtb.ssp.trust;

/** 현재 인스턴스가 연결된 지역 설정 저장소에서 활성 공급자 신뢰 스냅숏을 읽는다. */
public interface ProviderConfigReader {

    ProviderTrustSnapshot loadActiveSnapshot();
}
