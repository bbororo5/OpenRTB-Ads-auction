package com.bbororo.rtb.ssp.admission;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.trust.ProviderTrustSnapshot;
import java.util.Objects;

/** 지역 공급자 설정을 기준으로 경매 요청의 입장 권한을 판정한다. */
public final class ProviderRequestAuthorizer {

    private final ProviderTrustSnapshot trustSnapshot;

    public ProviderRequestAuthorizer(ProviderTrustSnapshot trustSnapshot) {
        this.trustSnapshot = Objects.requireNonNull(trustSnapshot);
    }

    public Authorization authorize(AuctionRequest request) {
        Objects.requireNonNull(request);

        if (!trustSnapshot.permits(request.providerId(), request.providerKeyId())) {
            return RejectedAuthorization.UNTRUSTED_PROVIDER;
        }
        return new AuthorizedRequest(request);
    }

    public sealed interface Authorization permits AuthorizedRequest, RejectedAuthorization {
    }

    public record AuthorizedRequest(AuctionRequest request) implements Authorization {

        public AuthorizedRequest {
            Objects.requireNonNull(request);
        }
    }

    public enum RejectedAuthorization implements Authorization {
        UNTRUSTED_PROVIDER
    }
}
