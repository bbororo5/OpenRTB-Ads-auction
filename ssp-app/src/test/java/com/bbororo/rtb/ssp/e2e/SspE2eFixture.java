package com.bbororo.rtb.ssp.e2e;

import com.bbororo.rtb.ssp.api.AuctionRenderApi;
import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * E2E가 관찰하는 외부 DSP와 저장소의 시험 경계다.
 *
 * <p>실제 SSP 조립체가 아직 없으므로 이 팩처리는 의도적으로 실패한다.</p>
 */
final class SspE2eFixture {

    private SspE2eFixture() {
    }

    static SspE2eFixture start() {
        throw new UnsupportedOperationException(
                "SSP 조립체가 아직 없다: 신뢰 스냅숏, DSP 입찰, 낙찰, 증표, 청구·전달을 연결해야 한다."
        );
    }

    AuctionRenderApi api() {
        throw new UnsupportedOperationException("not implemented");
    }

    int persistedClaimCount() {
        throw new UnsupportedOperationException("not implemented");
    }

    int pendingBillingDeliveryCount() {
        throw new UnsupportedOperationException("not implemented");
    }

    void deliverDueBilling(Instant now) {
        throw new UnsupportedOperationException("not implemented");
    }

    List<URI> deliveredBillingUrls() {
        throw new UnsupportedOperationException("not implemented");
    }
}
