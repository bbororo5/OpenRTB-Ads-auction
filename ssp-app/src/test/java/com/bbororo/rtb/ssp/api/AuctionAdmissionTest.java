package com.bbororo.rtb.ssp.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.bbororo.rtb.ssp.api.AuctionAdmission.AcceptedAdmission;
import com.bbororo.rtb.ssp.api.AuctionAdmission.RejectedAdmission;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.trust.ImmutableProviderTrustSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuctionAdmissionTest {

    private static final AuctionRequest REQUEST = new AuctionRequest(
            "provider-active",
            "key-active",
            "request-1",
            Instant.parse("2026-07-26T00:00:01Z"),
            List.of()
    );

    @Test
    void admitsOnlyAProviderWithAnActiveKeyInTheRegionalSnapshot() {
        AuctionAdmission admission = new AuctionAdmission(snapshot());

        AcceptedAdmission result = assertInstanceOf(AcceptedAdmission.class, admission.admit(REQUEST));

        assertEquals(REQUEST, result.request());
    }

    @Test
    void rejectsAnInactiveKeyBeforeDeduplication() {
        AuctionAdmission admission = new AuctionAdmission(snapshot());
        AuctionRequest inactiveKey = new AuctionRequest(
                "provider-active", "key-inactive", "request-1", REQUEST.deadline(), List.of());

        assertEquals(RejectedAdmission.UNTRUSTED_PROVIDER, admission.admit(inactiveKey));
    }

    @Test
    void rejectsAnInactiveProviderEvenWhenItsKeyIsKnown() {
        AuctionAdmission admission = new AuctionAdmission(new ImmutableProviderTrustSnapshot(
                1,
                Map.of("provider-inactive", new ImmutableProviderTrustSnapshot.ProviderPolicy(false, Set.of("key-active")))
        ));
        AuctionRequest inactiveProvider = new AuctionRequest(
                "provider-inactive", "key-active", "request-1", REQUEST.deadline(), List.of());

        assertEquals(RejectedAdmission.UNTRUSTED_PROVIDER, admission.admit(inactiveProvider));
    }

    private static ImmutableProviderTrustSnapshot snapshot() {
        return new ImmutableProviderTrustSnapshot(
                1,
                Map.of("provider-active", new ImmutableProviderTrustSnapshot.ProviderPolicy(true, Set.of("key-active")))
        );
    }
}
