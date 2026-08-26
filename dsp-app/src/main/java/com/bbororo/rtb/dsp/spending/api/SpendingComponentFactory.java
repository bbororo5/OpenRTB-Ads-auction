package com.bbororo.rtb.dsp.spending.api;

import com.bbororo.rtb.dsp.spending.internal.InMemoryLocalSpendingAuthority;
import com.bbororo.rtb.dsp.spending.internal.InMemoryReservationExpirationQueue;
import java.util.Collection;
import java.util.Objects;

/** 활성 캠페인 계정을 먼저 등록한 로컬 예산과 만료 큐를 조립한다. */
public final class SpendingComponentFactory {

    private SpendingComponentFactory() {
    }

    public static Components create(Collection<String> activeCampaignIds) {
        Objects.requireNonNull(activeCampaignIds, "activeCampaignIds");
        var expirations = new InMemoryReservationExpirationQueue();
        var authority = new InMemoryLocalSpendingAuthority(expirations);
        activeCampaignIds.forEach(authority::initializeCampaign);
        return new Components(
                authority,
                authority,
                authority,
                authority,
                authority,
                authority,
                expirations
        );
    }

    public record Components(
            ReservationAuthority reservations,
            ReservationFinalizer finalizer,
            ReservationStateView reservationStates,
            LeaseInstaller leaseInstaller,
            CampaignPacingView pacing,
            LocalLeaseSupplyView supply,
            ReservationExpirationSource expirations
    ) {
        public Components {
            Objects.requireNonNull(reservations, "reservations");
            Objects.requireNonNull(finalizer, "finalizer");
            Objects.requireNonNull(reservationStates, "reservationStates");
            Objects.requireNonNull(leaseInstaller, "leaseInstaller");
            Objects.requireNonNull(pacing, "pacing");
            Objects.requireNonNull(supply, "supply");
            Objects.requireNonNull(expirations, "expirations");
        }
    }
}
