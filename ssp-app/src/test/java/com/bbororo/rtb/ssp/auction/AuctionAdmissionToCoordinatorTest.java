package com.bbororo.rtb.ssp.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.bbororo.rtb.ssp.admission.AuctionAdmissionService;
import com.bbororo.rtb.ssp.admission.AuctionAdmissionService.AcceptedAuction;
import com.bbororo.rtb.ssp.admission.ProviderRequestAuthorizer;
import com.bbororo.rtb.ssp.contract.AuctionDeadline;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.contract.SspMessages.BidResponses;
import com.bbororo.rtb.ssp.contract.SspMessages.DspBid;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcomeKind;
import com.bbororo.rtb.ssp.deduplication.InMemoryAuctionDeduplicator;
import com.bbororo.rtb.ssp.notification.DspNotificationDelivery;
import com.bbororo.rtb.ssp.renderproof.AeadRenderProofService;
import com.bbororo.rtb.ssp.renderproof.AuctionResultAssembler;
import com.bbororo.rtb.ssp.trust.ImmutableProviderTrustSnapshot;
import com.bbororo.rtb.ssp.winner.FirstPriceWinnerSelector;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class AuctionAdmissionToCoordinatorTest {

    @Test
    void sendsTheFirstTrustedRequestThroughDeduplicationToTheCoordinator() {
        AuctionAdmissionService admission = new AuctionAdmissionService(
                new ProviderRequestAuthorizer(new ImmutableProviderTrustSnapshot(
                        1,
                        Map.of("provider-1", new ImmutableProviderTrustSnapshot.ProviderPolicy(true, Set.of("key-1")))
                )),
                new InMemoryAuctionDeduplicator(),
                new CoordinatingAuctionStarter(
                        new DeadlineBoundAuctionCoordinator(
                                batch -> new BidResponses(List.of(new DspCallOutcome(
                                        "project-dsp", DspCallOutcomeKind.VALID_BID, List.of(bid())
                                ))),
                                new FirstPriceWinnerSelector(),
                                List.of("project-dsp", "external-dsp-1", "external-dsp-2")
                        ),
                        Runnable::run,
                        new AuctionResultAssembler(
                                new AeadRenderProofService(
                                        "region-a",
                                        (byte) 1,
                                        Map.of((byte) 1, new SecretKeySpec(new byte[32], "AES"))
                                ),
                                Clock.systemUTC(),
                                URI.create("https://ssp.test/render")
                        ),
                        noOpNotificationDelivery()
                )
        );
        AuctionRequest request = new AuctionRequest(
                "provider-1", "key-1", "request-1", 180,
                List.of(new AuctionSlot("imp-1", 300, 250, 0))
        );

        AcceptedAuction accepted = assertInstanceOf(
                AcceptedAuction.class,
                admission.admit(request, AuctionDeadline.start(request.tmaxMillis(), System::nanoTime))
        );

        assertEquals(
                "project-dsp",
                accepted.result().toCompletableFuture().join()
                        .slots().getFirst().winningBid().dspId()
        );
    }

    private static DspNotificationDelivery noOpNotificationDelivery() {
        return new DspNotificationDelivery() {
            @Override
            public void sendAuctionNotices(
                    List<com.bbororo.rtb.ssp.contract.SspMessages.AuctionNotice> notices
            ) {
            }

            @Override
            public com.bbororo.rtb.ssp.notification.BillingDeliveryAttempt deliverDueBilling(java.time.Instant now) {
                return com.bbororo.rtb.ssp.notification.BillingDeliveryAttempt.empty();
            }
        };
    }

    private static DspBid bid() {
        URI callback = URI.create("https://project-dsp.example.test/notice");
        return new DspBid("project-dsp", "imp-1", "bid-1", 2_000, callback, callback, callback);
    }
}
