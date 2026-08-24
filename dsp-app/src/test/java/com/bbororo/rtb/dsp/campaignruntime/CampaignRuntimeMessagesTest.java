package com.bbororo.rtb.dsp.campaignruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bbororo.rtb.dsp.campaignruntime.CampaignRuntimeMessages.CampaignCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("캠페인 모델(CampaignRuntimeMessages) 단위 테스트")
class CampaignRuntimeMessagesTest {

    @Test
    @DisplayName("CPM 단가 변환: 0.001 KRW CPM은 노출 1건당 1 마이크로원과 수치가 일치한다")
    void cpmMilliKrwEqualsPerImpressionMicroKrwWithoutRounding() {
        var candidate = new CampaignCandidate("campaign-1", "creative-1", 1_234_567, 0);

        assertEquals(1_234_567, candidate.impressionAmountMicros());
    }
}
