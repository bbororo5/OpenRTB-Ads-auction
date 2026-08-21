package com.bbororo.rtb.dsp.campaign;

import com.bbororo.rtb.dsp.campaign.CampaignModels.CampaignSnapshot;
import java.util.concurrent.CompletionStage;

/** 시작 전에 완결된 캠페인 버전을 읽는 제어 경로 포트다. */
public interface CampaignSnapshotSource {

    CompletionStage<CampaignSnapshot> load(String requiredVersion);
}
