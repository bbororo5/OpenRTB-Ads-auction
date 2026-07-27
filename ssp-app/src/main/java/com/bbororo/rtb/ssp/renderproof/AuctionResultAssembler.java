package com.bbororo.rtb.ssp.renderproof;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionResult;
import com.bbororo.rtb.ssp.contract.SspMessages.ProofIssuance;
import com.bbororo.rtb.ssp.contract.SspMessages.SlotAuctionResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** 낙찰 결과마다 하나의 짧은 수명 렌더링 증표를 붙인다. */
public final class AuctionResultAssembler {

    private static final Duration RENDER_PROOF_VALIDITY = Duration.ofSeconds(2);

    private final RenderProofService renderProofService;
    private final Clock clock;

    public AuctionResultAssembler(RenderProofService renderProofService, Clock clock) {
        this.renderProofService = Objects.requireNonNull(renderProofService);
        this.clock = Objects.requireNonNull(clock);
    }

    public AuctionResult assemble(AuctionRequest request, AuctionOutcome outcome) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(outcome);
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(RENDER_PROOF_VALIDITY);
        return new AuctionResult(
                outcome.auctionId(),
                outcome.winners().winners().stream()
                        .map(winner -> new SlotAuctionResult(
                                winner,
                                renderProofService.issue(new ProofIssuance(
                                        request, outcome.auctionId(), winner, issuedAt, expiresAt
                                ))
                        ))
                        .toList()
        );
    }
}
