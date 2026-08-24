package com.bbororo.rtb.ssp.claim;

import com.bbororo.rtb.ssp.contract.SspMessages.BillingClaim;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderAcceptance;
import com.bbororo.rtb.ssp.contract.SspMessages.VerifiedRender;
import com.bbororo.rtb.ssp.contract.NoticeUrlTemplate.Context;
import com.bbororo.rtb.ssp.trust.ProviderTrustSnapshot;
import java.time.Duration;
import java.util.Objects;

/** 검증된 렌더링을 SSP 청구 근거와 전달 책임으로 바꾼다. */
public final class StoreBackedRenderClaimService implements RenderClaimService {

    private static final Duration BILLING_DELIVERY_WINDOW = Duration.ofSeconds(5);

    private final ClaimDeliveryStore store;
    private final ProviderTrustSnapshot trustSnapshot;

    public StoreBackedRenderClaimService(ClaimDeliveryStore store, ProviderTrustSnapshot trustSnapshot) {
        this.store = Objects.requireNonNull(store);
        this.trustSnapshot = Objects.requireNonNull(trustSnapshot);
    }

    @Override
    public RenderAcceptance acceptRender(VerifiedRender render) {
        Objects.requireNonNull(render);
        if (!trustSnapshot.isActive(render.providerId())) {
            return RenderAcceptance.REJECTED;
        }
        return store.recordClaimAndScheduleDelivery(new BillingClaim(
                render.providerId(),
                render.providerRequestId(),
                render.impId(),
                render.slotAuctionKey(),
                render.proofDigest(),
                render.dspId(),
                render.cpmMilliKrw(),
                render.billingUrlTemplate().render(new Context(
                        render.auctionId(),
                        render.impId(),
                        render.cpmMilliKrw(),
                        null,
                        render.impressionAt()
                )),
                render.auctionIssuedAt().plus(BILLING_DELIVERY_WINDOW)
        ));
    }
}
