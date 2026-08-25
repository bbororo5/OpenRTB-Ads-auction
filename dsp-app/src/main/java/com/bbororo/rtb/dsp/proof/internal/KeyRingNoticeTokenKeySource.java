package com.bbororo.rtb.dsp.proof.internal;

import com.bbororo.rtb.dsp.proof.spi.NoticeTokenKey;
import com.bbororo.rtb.dsp.proof.spi.NoticeTokenKeySource;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 활성 키 하나로 발급하고 키 링 전체로 교체 중 증표를 검증한다. */
public final class KeyRingNoticeTokenKeySource implements NoticeTokenKeySource {

    private final NoticeTokenKey activeKey;
    private final Map<String, NoticeTokenKey> keys;

    public KeyRingNoticeTokenKeySource(
            String activeKeyId,
            Map<String, NoticeTokenKey> keys
    ) {
        this.keys = Map.copyOf(Objects.requireNonNull(keys, "keys"));
        this.activeKey = this.keys.get(activeKeyId);
        if (activeKey == null) {
            throw new IllegalArgumentException("keys must contain activeKeyId");
        }
    }

    @Override
    public NoticeTokenKey activeKey() {
        return activeKey;
    }

    @Override
    public Optional<NoticeTokenKey> findKey(String keyId) {
        return Optional.ofNullable(keys.get(keyId));
    }
}
