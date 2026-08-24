package com.bbororo.rtb.dsp.campaignruntime.api;

import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.CampaignCandidate;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.RankCampaigns;
import java.util.List;

/** Bidding에 현재 스냅숏의 순서 있는 적격 후보를 제공한다. */
public interface CampaignCandidateSource {

    List<CampaignCandidate> rankCandidates(RankCampaigns request);
}
