package com.bbororo.rtb.ssp.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.bbororo.rtb.ssp.admission.ProviderRequestAuthorizer.AuthorizedRequest;
import com.bbororo.rtb.ssp.admission.ProviderRequestAuthorizer.RejectedAuthorization;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.trust.ImmutableProviderTrustSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProviderRequestAuthorizerTest {

    private static final AuctionRequest REQUEST = new AuctionRequest(
            "provider-active",
            "key-active",
            "request-1",
            180,
            List.of(new AuctionSlot("imp-1", 300, 250, 0))
    );

    @Test
    void authorizesOnlyAnActiveProviderWithAnActiveKeyInTheRegionalSnapshot() {
        ProviderRequestAuthorizer authorizer = new ProviderRequestAuthorizer(snapshot());

        AuthorizedRequest result = assertInstanceOf(AuthorizedRequest.class, authorizer.authorize(REQUEST));

        assertEquals(REQUEST, result.request());
    }

    @Test
    void rejectsAnInactiveKey() {
        ProviderRequestAuthorizer authorizer = new ProviderRequestAuthorizer(snapshot());
        AuctionRequest inactiveKey = new AuctionRequest(
                "provider-active", "key-inactive", "request-1", REQUEST.tmaxMillis(), REQUEST.slots());

        assertEquals(RejectedAuthorization.UNTRUSTED_PROVIDER, authorizer.authorize(inactiveKey));
    }

    @Test
    void rejectsAnInactiveProviderEvenWhenItsKeyIsKnown() {
        ProviderRequestAuthorizer authorizer = new ProviderRequestAuthorizer(new ImmutableProviderTrustSnapshot(
                1,
                Map.of("provider-inactive", new ImmutableProviderTrustSnapshot.ProviderPolicy(false, Set.of("key-active")))
        ));
        AuctionRequest inactiveProvider = new AuctionRequest(
                "provider-inactive", "key-active", "request-1", REQUEST.tmaxMillis(), REQUEST.slots());

        assertEquals(RejectedAuthorization.UNTRUSTED_PROVIDER, authorizer.authorize(inactiveProvider));
    }

    private static ImmutableProviderTrustSnapshot snapshot() {
        return new ImmutableProviderTrustSnapshot(
                1,
                Map.of("provider-active", new ImmutableProviderTrustSnapshot.ProviderPolicy(true, Set.of("key-active")))
        );
    }
}
