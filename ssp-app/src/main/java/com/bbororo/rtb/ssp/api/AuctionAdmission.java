package com.bbororo.rtb.ssp.api;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.trust.ProviderTrustSnapshot;
import java.util.Objects;

/** 경매 요청이 중복 방어 단계에 들어갈 자격이 있는지 판정한다. */
public final class AuctionAdmission {

    private final ProviderTrustSnapshot trustSnapshot;

    public AuctionAdmission(ProviderTrustSnapshot trustSnapshot) {
        this.trustSnapshot = Objects.requireNonNull(trustSnapshot);
    }

    public AdmissionResult admit(AuctionRequest request) {
        Objects.requireNonNull(request);

        if (!trustSnapshot.permits(request.providerId(), request.providerKeyId())) {
            return RejectedAdmission.UNTRUSTED_PROVIDER;
        }
        return new AcceptedAdmission(request);
    }

    public sealed interface AdmissionResult permits AcceptedAdmission, RejectedAdmission {
    }

    public record AcceptedAdmission(AuctionRequest request) implements AdmissionResult {

        public AcceptedAdmission {
            Objects.requireNonNull(request);
        }
    }

    public enum RejectedAdmission implements AdmissionResult {
        UNTRUSTED_PROVIDER
    }
}
