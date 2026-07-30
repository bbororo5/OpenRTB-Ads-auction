package com.bbororo.rtb.ssp.api;

import com.bbororo.rtb.ssp.admission.AuctionAdmissionService;
import com.bbororo.rtb.ssp.admission.AuctionAdmissionService.AcceptedAuction;
import com.bbororo.rtb.ssp.admission.AuctionAdmissionService.RejectedAuction;
import com.bbororo.rtb.ssp.claim.RenderClaimService;
import com.bbororo.rtb.ssp.contract.AuctionDeadline;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionResult;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderAcceptance;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderCompleted;
import com.bbororo.rtb.ssp.renderproof.RenderProofService;
import java.util.Objects;
import java.util.function.LongSupplier;

/** SSP 내부 컴포넌트를 공급자 경매·렌더링 진입 계약으로 조립한다. */
public final class DefaultAuctionRenderApi implements AuctionRenderApi {

    private final AuctionAdmissionService admissionService;
    private final RenderProofService renderProofService;
    private final RenderClaimService renderClaimService;
    private final LongSupplier nanoTime;

    public DefaultAuctionRenderApi(
            AuctionAdmissionService admissionService,
            RenderProofService renderProofService,
            RenderClaimService renderClaimService,
            LongSupplier nanoTime
    ) {
        this.admissionService = Objects.requireNonNull(admissionService);
        this.renderProofService = Objects.requireNonNull(renderProofService);
        this.renderClaimService = Objects.requireNonNull(renderClaimService);
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    @Override
    public AuctionResult auction(AuctionRequest request) {
        Objects.requireNonNull(request);
        AuctionDeadline deadline = AuctionDeadline.start(request.tmaxMillis(), nanoTime);
        return switch (admissionService.admit(request, deadline)) {
            case AcceptedAuction accepted -> accepted.result().toCompletableFuture().join();
            case RejectedAuction rejected -> throw new AuctionRejectedException(rejected.reason().name());
        };
    }

    @Override
    public RenderAcceptance completeRender(RenderCompleted completed) {
        Objects.requireNonNull(completed);
        return renderProofService.verify(completed)
                .map(renderClaimService::acceptRender)
                .orElse(RenderAcceptance.REJECTED);
    }
}
