package com.bbororo.rtb.ssp.contract;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.contract.SspMessages.DspBid;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcomeKind;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuctionContractTest {

    @Test
    void rejectsANegativeSlotFloor() {
        assertThrows(IllegalArgumentException.class, () -> new AuctionSlot("imp-1", -1));
    }

    @Test
    void requiresBidsOnlyForAValidBidOutcome() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DspCallOutcome("dsp-1", DspCallOutcomeKind.VALID_BID, List.of())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new DspCallOutcome("dsp-1", DspCallOutcomeKind.NO_BID, List.of(bid()))
        );
    }

    private static DspBid bid() {
        URI callback = URI.create("https://dsp.example.test/notice");
        return new DspBid("dsp-1", "imp-1", "bid-1", 1_000, callback, callback, callback);
    }
}
