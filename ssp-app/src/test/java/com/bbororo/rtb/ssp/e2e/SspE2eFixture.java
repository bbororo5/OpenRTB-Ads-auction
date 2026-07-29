package com.bbororo.rtb.ssp.e2e;

import com.bbororo.rtb.ssp.admission.AuctionAdmissionService;
import com.bbororo.rtb.ssp.admission.ProviderRequestAuthorizer;
import com.bbororo.rtb.ssp.api.AuctionRenderApi;
import com.bbororo.rtb.ssp.api.DefaultAuctionRenderApi;
import com.bbororo.rtb.ssp.auction.CoordinatingAuctionStarter;
import com.bbororo.rtb.ssp.auction.DeadlineBoundAuctionCoordinator;
import com.bbororo.rtb.ssp.claim.InMemoryClaimDeliveryStore;
import com.bbororo.rtb.ssp.claim.StoreBackedRenderClaimService;
import com.bbororo.rtb.ssp.contract.SspMessages.BidResponses;
import com.bbororo.rtb.ssp.contract.SspMessages.DspBid;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcomeKind;
import com.bbororo.rtb.ssp.deduplication.InMemoryAuctionDeduplicator;
import com.bbororo.rtb.ssp.notification.DspNotificationDelivery;
import com.bbororo.rtb.ssp.notification.StoreBackedDspNotificationDelivery;
import com.bbororo.rtb.ssp.renderproof.AeadRenderProofService;
import com.bbororo.rtb.ssp.renderproof.AuctionResultAssembler;
import com.bbororo.rtb.ssp.trust.ImmutableProviderTrustSnapshot;
import com.bbororo.rtb.ssp.winner.FirstPriceWinnerSelector;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;

/**
 * E2E가 관찰하는 외부 DSP와 저장소의 시험 경계다.
 */
final class SspE2eFixture {

    private static final Instant STARTED_AT = Instant.parse("2026-07-24T00:00:00Z");

    private final AuctionRenderApi api;
    private final InMemoryClaimDeliveryStore store;
    private final DspNotificationDelivery notificationDelivery;
    private final List<URI> deliveredBillingUrls;

    private SspE2eFixture(
            AuctionRenderApi api,
            InMemoryClaimDeliveryStore store,
            DspNotificationDelivery notificationDelivery,
            List<URI> deliveredBillingUrls
    ) {
        this.api = api;
        this.store = store;
        this.notificationDelivery = notificationDelivery;
        this.deliveredBillingUrls = deliveredBillingUrls;
    }

    static SspE2eFixture start() {
        var trust = new ImmutableProviderTrustSnapshot(
                1,
                Map.of("provider-a", new ImmutableProviderTrustSnapshot.ProviderPolicy(
                        true, Set.of("key-2026-01")
                ))
        );
        var store = new InMemoryClaimDeliveryStore();
        var proofService = new AeadRenderProofService(
                "region-a",
                (byte) 1,
                Map.of((byte) 1, new SecretKeySpec(new byte[32], "AES"))
        );
        var coordinator = new DeadlineBoundAuctionCoordinator(
                ignored -> new BidResponses(List.of(new DspCallOutcome(
                        "project-dsp",
                        DspCallOutcomeKind.VALID_BID,
                        List.of(projectDspBid())
                ))),
                new FirstPriceWinnerSelector(),
                List.of("project-dsp", "external-dsp-1", "external-dsp-2")
        );
        var admission = new AuctionAdmissionService(
                new ProviderRequestAuthorizer(trust),
                new InMemoryAuctionDeduplicator(),
                new CoordinatingAuctionStarter(coordinator, Runnable::run)
        );
        Clock clock = Clock.fixed(STARTED_AT, ZoneOffset.UTC);
        List<URI> delivered = new ArrayList<>();
        DspNotificationDelivery notificationDelivery = new StoreBackedDspNotificationDelivery(
                store,
                url -> {
                    if (url.getPath().contains("/burl/")) {
                        delivered.add(url);
                    }
                    return com.bbororo.rtb.ssp.contract.SspMessages.DeliveryOutcome.DELIVERED;
                }
        );
        AuctionRenderApi api = new DefaultAuctionRenderApi(
                admission,
                new AuctionResultAssembler(
                        proofService,
                        clock,
                        URI.create("https://region-a.ssp.test/publisher/render")
                ),
                proofService,
                new StoreBackedRenderClaimService(store, trust),
                notificationDelivery,
                System::nanoTime
        );
        return new SspE2eFixture(api, store, notificationDelivery, delivered);
    }

    AuctionRenderApi api() {
        return api;
    }

    int persistedClaimCount() {
        return store.recordedClaimCount();
    }

    int pendingBillingDeliveryCount() {
        return store.pendingDeliveryCount();
    }

    void deliverDueBilling(Instant now) {
        notificationDelivery.deliverDueBilling(now);
    }

    List<URI> deliveredBillingUrls() {
        return List.copyOf(deliveredBillingUrls);
    }

    private static DspBid projectDspBid() {
        return new DspBid(
                "project-dsp",
                "imp-1",
                "bid-1",
                2_000,
                URI.create("https://project-dsp.test/nurl/reservation-1"),
                URI.create("https://project-dsp.test/lurl/reservation-1"),
                URI.create("https://project-dsp.test/burl/reservation-1")
        );
    }
}
