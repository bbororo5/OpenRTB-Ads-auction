package com.bbororo.rtb.dsp.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bbororo.rtb.dsp.campaign.CampaignMessages.CampaignCandidate;
import org.junit.jupiter.api.Test;

class CampaignMessagesTest {

    @Test
    void cpmMilliKrwEqualsPerImpressionMicroKrwWithoutRounding() {
        var candidate = new CampaignCandidate("campaign-1", "creative-1", 1_234_567, 0);

        assertEquals(1_234_567, candidate.impressionAmountMicros());
    }
}
