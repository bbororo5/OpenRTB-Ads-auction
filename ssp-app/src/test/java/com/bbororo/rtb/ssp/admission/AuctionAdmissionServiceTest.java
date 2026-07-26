package com.bbororo.rtb.ssp.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.bbororo.rtb.ssp.admission.AuctionAdmissionService.AcceptedAuction;
import com.bbororo.rtb.ssp.admission.AuctionAdmissionService.RejectedAuction;
import com.bbororo.rtb.ssp.admission.ProviderRequestAuthorizer.RejectedAuthorization;
import com.bbororo.rtb.ssp.contract.AuctionDeadline;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionResult;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderProof;
import com.bbororo.rtb.ssp.deduplication.InMemoryAuctionDeduplicator;
import com.bbororo.rtb.ssp.trust.ImmutableProviderTrustSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AuctionAdmissionServiceTest {

    private static final AuctionResult RESULT = new AuctionResult("auction-1", List.of(), new RenderProof("proof"));

    @Test
    void rejectsAnUntrustedRequestBeforeItCanStartAnAuction() {
        AuctionAdmissionService service = serviceFor("key-active");
        AtomicInteger starts = new AtomicInteger();

        RejectedAuction rejected = assertInstanceOf(RejectedAuction.class, service.admit(
                request("key-inactive", "request-1"),
                deadline(),
                auction -> {
                    starts.incrementAndGet();
                    return CompletableFuture.completedFuture(RESULT);
                }
        ));

        assertEquals(RejectedAuthorization.UNTRUSTED_PROVIDER, rejected.reason());
        assertEquals(0, starts.get());
    }

    @Test
    void sendsTrustedDuplicatesToTheSameSingleFlightAuction() {
        AuctionAdmissionService service = serviceFor("key-active");
        CompletableFuture<AuctionResult> firstAuction = new CompletableFuture<>();
        AtomicInteger starts = new AtomicInteger();

        AcceptedAuction first = assertInstanceOf(AcceptedAuction.class, service.admit(
                request("key-active", "request-1"),
                deadline(),
                auction -> {
                    starts.incrementAndGet();
                    return firstAuction;
                }
        ));
        AcceptedAuction duplicate = assertInstanceOf(AcceptedAuction.class, service.admit(
                request("key-active", "request-1"),
                deadline(),
                auction -> {
                    throw new AssertionError("duplicate must not start another auction");
                }
        ));

        assertEquals(1, starts.get());
        assertSame(first.result(), duplicate.result());

        firstAuction.complete(RESULT);
        assertEquals(RESULT, duplicate.result().toCompletableFuture().join());
    }

    private static AuctionAdmissionService serviceFor(String activeKey) {
        ProviderRequestAuthorizer authorizer = new ProviderRequestAuthorizer(new ImmutableProviderTrustSnapshot(
                1,
                Map.of("provider-active", new ImmutableProviderTrustSnapshot.ProviderPolicy(true, Set.of(activeKey)))
        ));
        return new AuctionAdmissionService(authorizer, new InMemoryAuctionDeduplicator());
    }

    private static AuctionRequest request(String keyId, String requestId) {
        return new AuctionRequest(
                "provider-active",
                keyId,
                requestId,
                180,
                List.of(new AuctionSlot("imp-1", 0))
        );
    }

    private static AuctionDeadline deadline() {
        return AuctionDeadline.start(180, System::nanoTime);
    }
}
