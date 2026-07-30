package com.bbororo.rtb.ssp.admission;

import com.bbororo.rtb.ssp.admission.ProviderRequestAuthorizer.AuthorizedRequest;
import com.bbororo.rtb.ssp.admission.ProviderRequestAuthorizer.RejectedAuthorization;
import com.bbororo.rtb.ssp.contract.AuctionDeadline;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionResult;
import com.bbororo.rtb.ssp.deduplication.AuctionDeduplicator;
import com.bbororo.rtb.ssp.deduplication.AuctionStarter;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** 신뢰 확인을 통과한 요청만 로컬 중복 방어와 경매 시작으로 전달한다. */
public final class AuctionAdmissionService {

    private final ProviderRequestAuthorizer authorizer;
    private final AuctionDeduplicator deduplicator;
    private final AuctionStarter starter;

    public AuctionAdmissionService(
            ProviderRequestAuthorizer authorizer,
            AuctionDeduplicator deduplicator,
            AuctionStarter starter
    ) {
        this.authorizer = Objects.requireNonNull(authorizer);
        this.deduplicator = Objects.requireNonNull(deduplicator);
        this.starter = Objects.requireNonNull(starter);
    }

    public Admission admit(AuctionRequest request, AuctionDeadline deadline) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(deadline);
        Objects.requireNonNull(starter);

        return switch (authorizer.authorize(request)) {
            case AuthorizedRequest authorized -> new AcceptedAuction(
                    deduplicator.execute(authorized.request(), deadline, starter)
            );
            case RejectedAuthorization rejected -> new RejectedAuction(rejected);
        };
    }

    public sealed interface Admission permits AcceptedAuction, RejectedAuction {
    }

    public record AcceptedAuction(CompletionStage<AuctionResult> result) implements Admission {

        public AcceptedAuction {
            Objects.requireNonNull(result);
        }
    }

    public record RejectedAuction(RejectedAuthorization reason) implements Admission {

        public RejectedAuction {
            Objects.requireNonNull(reason);
        }
    }
}
