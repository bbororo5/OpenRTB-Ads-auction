package com.bbororo.rtb.ssp.claim;

import com.bbororo.rtb.ssp.contract.SspMessages.BillingClaim;
import com.bbororo.rtb.ssp.contract.SspMessages.DeliveryOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.DeliveryLease;
import com.bbororo.rtb.ssp.contract.SspMessages.LeasedBillingDelivery;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderAcceptance;
import java.time.Instant;
import java.util.Optional;

/**
 * 청구와 과금 통지 전달 작업의 내구성 포트다.
 *
 * <p>이 포트는 C3 컴포넌트가 아닌 PostgreSQL 어댑터의 경계다.</p>
 */
public interface ClaimDeliveryStore {

    RenderAcceptance recordClaimAndScheduleDelivery(BillingClaim claim);

    Optional<LeasedBillingDelivery> leaseDueDelivery(Instant now);

    Optional<Instant> completeOrReleaseDelivery(
            DeliveryLease lease,
            DeliveryOutcome outcome,
            Instant now
    );
}
