package com.bbororo.rtb.ssp.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.bbororo.rtb.ssp.admission.AuctionAdmissionService.AcceptedAuction;
import com.bbororo.rtb.ssp.admission.AuctionAdmissionService.RejectedAuction;
import com.bbororo.rtb.ssp.admission.ProviderRequestAuthorizer.RejectedAuthorization;
import com.bbororo.rtb.ssp.contract.AuctionDeadline;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionResult;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.deduplication.AuctionStarter;
import com.bbororo.rtb.ssp.deduplication.InMemoryAuctionDeduplicator;
import com.bbororo.rtb.ssp.trust.ImmutableProviderTrustSnapshot;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AuctionAdmissionServiceTest {

    private static final AuctionResult RESULT = new AuctionResult(
            "auction-1", List.of(), URI.create("https://ssp.test/render")
    );

    @Test
    void rejectsAnUntrustedRequestBeforeItCanStartAnAuction() {
        AtomicInteger starts = new AtomicInteger();
        AuctionAdmissionService service = serviceFor("key-active", auction -> {
            starts.incrementAndGet();
            return CompletableFuture.completedFuture(RESULT);
        });

        RejectedAuction rejected = assertInstanceOf(RejectedAuction.class, service.admit(
                request("key-inactive", "request-1"),
                deadline()
        ));

        assertEquals(RejectedAuthorization.UNTRUSTED_PROVIDER, rejected.reason());
        assertEquals(0, starts.get());
    }

    @Test
    void sendsTrustedDuplicatesToTheSameSingleFlightAuction() {
        CompletableFuture<AuctionResult> firstAuction = new CompletableFuture<>();
        AtomicInteger starts = new AtomicInteger();
        AuctionAdmissionService service = serviceFor("key-active", auction -> {
            starts.incrementAndGet();
            return firstAuction;
        });

        AcceptedAuction first = assertInstanceOf(AcceptedAuction.class, service.admit(
                request("key-active", "request-1"),
                deadline()
        ));
        AcceptedAuction duplicate = assertInstanceOf(AcceptedAuction.class, service.admit(
                request("key-active", "request-1"),
                deadline()
        ));

        assertEquals(1, starts.get());
        firstAuction.complete(RESULT);
        assertEquals(RESULT, duplicate.result().toCompletableFuture().join());
    }

    private static AuctionAdmissionService serviceFor(String activeKey, AuctionStarter starter) {
        ProviderRequestAuthorizer authorizer = new ProviderRequestAuthorizer(new ImmutableProviderTrustSnapshot(
                1,
                Map.of("provider-active", new ImmutableProviderTrustSnapshot.ProviderPolicy(true, Set.of(activeKey)))
        ));
        return new AuctionAdmissionService(authorizer, new InMemoryAuctionDeduplicator(), starter);
    }

    private static AuctionRequest request(String keyId, String requestId) {
        return new AuctionRequest(
                "provider-active",
                keyId,
                requestId,
                180,
                List.of(new AuctionSlot("imp-1", 300, 250, 0))
        );
    }

    private static AuctionDeadline deadline() {
        return AuctionDeadline.start(180, System::nanoTime);
    }
}
