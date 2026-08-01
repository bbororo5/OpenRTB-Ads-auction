package com.bbororo.rtb.dsp.campaign;

import com.bbororo.rtb.dsp.campaign.CampaignMessages.CampaignCandidate;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.CampaignSnapshot;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.RankCampaigns;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.SnapshotInstallResult;
import java.util.List;

/** 불변 캠페인 스냅숏에서 적격 후보를 찾아 페이싱 지연 순으로 반환한다. */
public interface CampaignSelector {

    SnapshotInstallResult install(CampaignSnapshot snapshot);

    List<CampaignCandidate> rankCandidates(RankCampaigns request);
}
