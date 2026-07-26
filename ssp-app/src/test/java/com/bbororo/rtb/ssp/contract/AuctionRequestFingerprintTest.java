package com.bbororo.rtb.ssp.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuctionRequestFingerprintTest {

    @Test
    void ignoresCredentialRotationButKeepsTheSameAuctionRequest() {
        AuctionRequest original = request("key-old", Instant.parse("2026-07-26T00:00:01Z"), List.of("imp-1"));
        AuctionRequest retry = request("key-new", Instant.parse("2026-07-26T00:00:01Z"), List.of("imp-1"));

        assertEquals(original.fingerprint(), retry.fingerprint());
    }

    @Test
    void changesWhenTheRequestedSlotsChange() {
        AuctionRequest original = request("key-1", Instant.parse("2026-07-26T00:00:01Z"), List.of("imp-1"));
        AuctionRequest changed = request("key-1", Instant.parse("2026-07-26T00:00:01Z"), List.of("imp-2"));

        assertNotEquals(original.fingerprint(), changed.fingerprint());
    }

    @Test
    void changesWhenTheAuctionDeadlineChanges() {
        AuctionRequest original = request("key-1", Instant.parse("2026-07-26T00:00:01Z"), List.of("imp-1"));
        AuctionRequest changed = request("key-1", Instant.parse("2026-07-26T00:00:02Z"), List.of("imp-1"));

        assertNotEquals(original.fingerprint(), changed.fingerprint());
    }

    private static AuctionRequest request(String keyId, Instant deadline, List<String> impIds) {
        return new AuctionRequest(
                "provider-1",
                keyId,
                "request-1",
                deadline,
                impIds.stream().map(AuctionSlot::new).toList()
        );
    }
}
