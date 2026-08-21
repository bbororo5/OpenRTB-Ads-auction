package com.bbororo.rtb.dsp.campaign;

import com.bbororo.rtb.dsp.campaign.CampaignModels.CampaignCandidate;
import com.bbororo.rtb.dsp.campaign.CampaignModels.CampaignSnapshot;
import com.bbororo.rtb.dsp.campaign.CampaignModels.RankCampaigns;
import com.bbororo.rtb.dsp.campaign.CampaignModels.SnapshotInstallResult;
import java.util.List;

/**
 * 불변 캠페인 스냅샷을 인메모리에 적재하고, 실시간 입찰 요청에 적합한 후보를 L1 캐시에서 선별·순위화하여 반환하는 선택기 인터페이스다.
 */
public interface CampaignSelector {

    /** 새로운 캠페인 스냅샷을 인메모리 역색인으로 원자적 교체 적재한다. */
    SnapshotInstallResult install(CampaignSnapshot snapshot);

    /** 요청된 슬롯 규격, 유효 시간, 바닥가에 적격한 후보들을 페이싱 지연율 내림차순으로 순위화하여 반환한다. */
    List<CampaignCandidate> rankCandidates(RankCampaigns request);
}
