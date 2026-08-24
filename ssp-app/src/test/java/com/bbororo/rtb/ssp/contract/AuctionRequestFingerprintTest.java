package com.bbororo.rtb.ssp.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuctionRequestFingerprintTest {

    @Test
    void ignoresCredentialRotationButKeepsTheSameAuctionRequest() {
        AuctionRequest original = request("key-old", 180, List.of("imp-1"));
        AuctionRequest retry = request("key-new", 180, List.of("imp-1"));

        assertEquals(original.fingerprint(), retry.fingerprint());
    }

    @Test
    void changesWhenTheRequestedSlotsChange() {
        AuctionRequest original = request("key-1", 180, List.of("imp-1"));
        AuctionRequest changed = request("key-1", 180, List.of("imp-2"));

        assertNotEquals(original.fingerprint(), changed.fingerprint());
    }

    @Test
    void changesWhenTheTmaxChanges() {
        AuctionRequest original = request("key-1", 180, List.of("imp-1"));
        AuctionRequest changed = request("key-1", 100, List.of("imp-1"));

        assertNotEquals(original.fingerprint(), changed.fingerprint());
    }

    @Test
    void changesWhenASlotFloorChanges() {
        AuctionRequest original = requestWithSlots("key-1", 180, List.of(new AuctionSlot("imp-1", 300, 250, 1_000_000)));
        AuctionRequest changed = requestWithSlots("key-1", 180, List.of(new AuctionSlot("imp-1", 300, 250, 1_000_001)));

        assertNotEquals(original.fingerprint(), changed.fingerprint());
    }

    @Test
    void changesWhenASlotDimensionChanges() {
        AuctionRequest original = requestWithSlots(
                "key-1", 180, List.of(new AuctionSlot("imp-1", 300, 250, 1_000_000)));
        AuctionRequest changed = requestWithSlots(
                "key-1", 180, List.of(new AuctionSlot("imp-1", 320, 250, 1_000_000)));

        assertNotEquals(original.fingerprint(), changed.fingerprint());
    }

    private static AuctionRequest request(String keyId, int tmaxMillis, List<String> impIds) {
        return requestWithSlots(
                keyId,
                tmaxMillis,
                impIds.stream().map(impId -> new AuctionSlot(impId, 300, 250, 0)).toList()
        );
    }

    private static AuctionRequest requestWithSlots(String keyId, int tmaxMillis, List<AuctionSlot> slots) {
        return new AuctionRequest(
                "provider-1",
                keyId,
                "request-1",
                tmaxMillis,
                slots
        );
    }
}
