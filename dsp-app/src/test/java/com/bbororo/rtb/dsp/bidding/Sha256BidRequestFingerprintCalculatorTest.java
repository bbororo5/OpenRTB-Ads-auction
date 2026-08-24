package com.bbororo.rtb.dsp.bidding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Impression;
import java.util.List;
import org.junit.jupiter.api.Test;

class Sha256BidRequestFingerprintCalculatorTest {

    private final BidRequestFingerprintCalculator calculator = new Sha256BidRequestFingerprintCalculator();

    @Test
    void equivalentRequestsProduceTheSameVersionedFingerprint() {
        var first = request("request-1", 50, impression("imp-1", 300, 250, 500),
                impression("imp-2", 728, 90, 1_000));
        var reordered = request("request-2", 50, impression("imp-2", 728, 90, 1_000),
                impression("imp-1", 300, 250, 500));

        var firstFingerprint = calculator.calculate(first);
        var reorderedFingerprint = calculator.calculate(reordered);

        assertEquals(1, firstFingerprint.version());
        assertEquals(64, firstFingerprint.digestHex().length());
        assertEquals(firstFingerprint, reorderedFingerprint,
                "requestId와 슬롯 배열 순서는 경매 내용 지문을 바꾸지 않아야 한다");
    }

    @Test
    void everyCurrentBidDecisionInputChangesTheFingerprint() {
        var baseline = request("request-1", 50, impression("imp-1", 300, 250, 500));
        var baselineFingerprint = calculator.calculate(baseline);

        List<BidRequest> differentMeaning = List.of(
                request("request-1", 51, impression("imp-1", 300, 250, 500)),
                request("request-1", 50, impression("imp-1", 301, 250, 500)),
                request("request-1", 50, impression("imp-1", 300, 251, 500)),
                request("request-1", 50, impression("imp-1", 300, 250, 501)),
                request("request-1", 50,
                        impression("imp-1", 300, 250, 500),
                        impression("imp-2", 300, 250, 500))
        );

        for (BidRequest changed : differentMeaning) {
            assertNotEquals(baselineFingerprint, calculator.calculate(changed), changed.toString());
        }
    }

    @Test
    void changingAnImpressionIdChangesTheRequestFingerprint() {
        var first = request("request-1", 50, impression("imp-1", 300, 250, 500));
        var changed = request("request-1", 50, impression("imp-2", 300, 250, 500));

        assertNotEquals(calculator.calculate(first), calculator.calculate(changed));
    }

    private static BidRequest request(String requestId, int tmaxMillis, Impression... impressions) {
        return new BidRequest(requestId, tmaxMillis, List.of(impressions));
    }

    private static Impression impression(String id, int width, int height, long bidFloor) {
        return new Impression(id, width, height, bidFloor, 2);
    }
}
