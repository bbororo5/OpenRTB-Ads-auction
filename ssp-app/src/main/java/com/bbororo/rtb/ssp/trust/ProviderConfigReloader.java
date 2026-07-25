package com.bbororo.rtb.ssp.trust;

import java.util.Objects;

/** 지역 저장소에서 완결된 새 설정을 읽어 현재 스냅숏에 반영한다. */
public final class ProviderConfigReloader {

    private final ProviderConfigReader reader;
    private final ProviderTrustSnapshotHolder snapshots;

    public ProviderConfigReloader(ProviderConfigReader reader, ProviderTrustSnapshotHolder snapshots) {
        this.reader = Objects.requireNonNull(reader);
        this.snapshots = Objects.requireNonNull(snapshots);
    }

    /** 읽기·조립이 성공했을 때만 더 새 버전을 공개한다. */
    public boolean refresh() {
        return snapshots.replaceIfNewer(reader.loadActiveSnapshot());
    }
}
