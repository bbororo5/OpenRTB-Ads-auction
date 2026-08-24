package com.bbororo.rtb.dsp.campaignruntime.api;

import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.CampaignSnapshot;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.SnapshotInstallResult;

/** 검증된 완결 캠페인 버전을 런타임에 원자적으로 공개한다. */
public interface CampaignSnapshotInstaller {

    SnapshotInstallResult install(CampaignSnapshot snapshot);
}
