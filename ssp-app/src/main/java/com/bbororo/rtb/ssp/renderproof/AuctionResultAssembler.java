package com.bbororo.rtb.ssp.renderproof;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionResult;
import com.bbororo.rtb.ssp.contract.SspMessages.ProofIssuance;
import com.bbororo.rtb.ssp.contract.SspMessages.SlotAuctionResult;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** 낙찰 결과마다 하나의 짧은 수명 렌더링 증표를 붙인다. */
public final class AuctionResultAssembler {

    private static final Duration RENDER_PROOF_VALIDITY = Duration.ofSeconds(2);

    private final RenderProofService renderProofService;
    private final Clock clock;
    private final URI renderCompletionUrl;

    public AuctionResultAssembler(
            RenderProofService renderProofService,
            Clock clock,
            URI renderCompletionUrl
    ) {
        this.renderProofService = Objects.requireNonNull(renderProofService);
        this.clock = Objects.requireNonNull(clock);
        this.renderCompletionUrl = requireHttpUrl(renderCompletionUrl);
    }

    public AuctionResult assemble(AuctionRequest request, AuctionOutcome outcome) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(outcome);
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
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
                        .toList(),
                renderCompletionUrl
        );
    }

    private static URI requireHttpUrl(URI value) {
        Objects.requireNonNull(value, "renderCompletionUrl");
        if (!value.isAbsolute()
                || (!"http".equalsIgnoreCase(value.getScheme())
                && !"https".equalsIgnoreCase(value.getScheme()))) {
            throw new IllegalArgumentException("renderCompletionUrl must be an absolute HTTP URL");
        }
        return value;
    }
}
