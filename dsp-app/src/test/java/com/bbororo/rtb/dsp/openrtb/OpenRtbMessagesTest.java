package com.bbororo.rtb.dsp.openrtb;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Impression;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenRtbMessagesTest {

    @Test
    void requestRejectsDuplicateImpressionIds() {
        var impression = new Impression("imp-1", 300, 250, 1_000_000, 2);

        assertThrows(
                IllegalArgumentException.class,
                () -> new BidRequest("auction-1", 50, List.of(impression, impression))
        );
    }

    @Test
    void requestRejectsDeadlineBeyondProjectContract() {
        var impression = new Impression("imp-1", 300, 250, 1_000_000, 2);

        assertThrows(
                IllegalArgumentException.class,
                () -> new BidRequest("auction-1", 181, List.of(impression))
        );
    }

    @Test
    void impressionRequiresTheAgreedTwoSecondRenderWindow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Impression("imp-1", 300, 250, 1_000_000, 3)
        );
    }
}
