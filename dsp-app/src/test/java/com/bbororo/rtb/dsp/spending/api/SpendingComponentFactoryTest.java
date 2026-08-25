package com.bbororo.rtb.dsp.spending.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpendingComponentFactoryTest {

    @Test
    void registersActiveCampaignsBeforeTheFirstLeaseRefill() {
        var spending = SpendingComponentFactory.create(
                List.of("campaign-2", "campaign-1"));

        var snapshots = spending.supply().supplySnapshots();

        assertEquals(
                List.of("campaign-1", "campaign-2"),
                snapshots.stream().map(
                        SpendingMessages.LeaseSupplySnapshot::campaignId).toList());
        assertEquals(0, snapshots.getFirst().reusableMicros());
        assertEquals(0, snapshots.getFirst().openLeaseCount());
    }
}
