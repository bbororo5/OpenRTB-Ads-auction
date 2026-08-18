package com.bbororo.rtb.dsp.notification;

import java.util.Optional;

/** 요청 처리 중 외부 통신 없이 현재 발급 키와 검증 키를 제공한다. */
@FunctionalInterface
public interface NoticeTokenKeySource {

    NoticeTokenKey activeKey();

    default Optional<NoticeTokenKey> findKey(String keyId) {
        var active = activeKey();
        return active != null && active.keyId().equals(keyId)
                ? Optional.of(active)
                : Optional.empty();
    }
}
