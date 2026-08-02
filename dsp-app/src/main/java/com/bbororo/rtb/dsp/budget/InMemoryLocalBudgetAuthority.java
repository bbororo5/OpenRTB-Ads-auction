package com.bbororo.rtb.dsp.budget;

import com.bbororo.rtb.dsp.budget.BudgetMessages.CommitReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ExpireReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.InstallLease;
import com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseBalance;
import com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseInstallResult;
import com.bbororo.rtb.dsp.budget.BudgetMessages.PacingPosition;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReleaseReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationFinalization;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationRejected;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationResult;
import com.bbororo.rtb.dsp.budget.BudgetMessages.TryReserve;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** 캠페인별 잠금과 인스턴스 용량 상한을 사용하는 로컬 예산 권한 구현이다. */
public final class InMemoryLocalBudgetAuthority implements LocalBudgetAuthority {

    public static final int DEFAULT_INSTANCE_CAPACITY = 20_000;
    public static final int DEFAULT_CAMPAIGN_CAPACITY = 2_500;
    public static final int DEFAULT_LEASE_CAPACITY = 64;

    private final ConcurrentHashMap<String, CampaignBudgetAccount> accounts = new ConcurrentHashMap<>();
    private final Semaphore instanceCapacity;
    private final int campaignCapacity;
    private final int leaseCapacity;
    private final Clock clock;
    private final Supplier<String> reservationIds;
    private final ReservationExpirationSink expirationSink;

    public InMemoryLocalBudgetAuthority(ReservationExpirationSink expirationSink) {
        this(
                DEFAULT_INSTANCE_CAPACITY,
                DEFAULT_CAMPAIGN_CAPACITY,
                DEFAULT_LEASE_CAPACITY,
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString(),
                expirationSink
        );
    }

    InMemoryLocalBudgetAuthority(
            int instanceCapacity,
            int campaignCapacity,
            int leaseCapacity,
            Clock clock,
            Supplier<String> reservationIds,
            ReservationExpirationSink expirationSink
    ) {
        if (instanceCapacity <= 0 || campaignCapacity <= 0 || leaseCapacity <= 0) {
            throw new IllegalArgumentException("capacities must be positive");
        }
        this.instanceCapacity = new Semaphore(instanceCapacity);
        this.campaignCapacity = campaignCapacity;
        this.leaseCapacity = leaseCapacity;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.reservationIds = Objects.requireNonNull(reservationIds, "reservationIds");
        this.expirationSink = Objects.requireNonNull(expirationSink, "expirationSink");
    }

    @Override
    public ReservationResult tryReserve(TryReserve command) {
        Objects.requireNonNull(command, "command");
        if (!instanceCapacity.tryAcquire()) {
            return new ReservationRejected(BudgetMessages.ReservationRejection.INSTANCE_CAPACITY_EXCEEDED);
        }

        CampaignBudgetAccount account = accounts.get(command.campaignId());
        if (account == null) {
            instanceCapacity.release();
            return new ReservationRejected(BudgetMessages.ReservationRejection.NO_ACTIVE_LEASE);
        }

        ReservationResult result;
        try {
            result = account.tryReserve(command, clock.instant(), reservationIds, expirationSink);
        } catch (RuntimeException failure) {
            instanceCapacity.release();
            throw failure;
        }
        if (result instanceof ReservationRejected) {
            instanceCapacity.release();
        }
        return result;
    }

    @Override
    public ReservationFinalization release(ReleaseReservation command) {
        Objects.requireNonNull(command, "command");
        CampaignBudgetAccount account = accounts.get(command.reservation().campaignId());
        if (account == null) {
            return ReservationFinalization.UNKNOWN_RESERVATION;
        }
        return releaseCapacity(account.release(command));
    }

    @Override
    public ReservationFinalization commit(CommitReservation command) {
        Objects.requireNonNull(command, "command");
        CampaignBudgetAccount account = accounts.get(command.reservation().campaignId());
        if (account == null) {
            return ReservationFinalization.UNKNOWN_RESERVATION;
        }
        return releaseCapacity(account.commit(command));
    }

    @Override
    public ReservationFinalization expire(ExpireReservation command) {
        Objects.requireNonNull(command, "command");
        CampaignBudgetAccount account = accounts.get(command.reservation().campaignId());
        if (account == null) {
            return ReservationFinalization.UNKNOWN_RESERVATION;
        }
        return releaseCapacity(account.expire(command));
    }

    @Override
    public LeaseInstallResult install(InstallLease command) {
        Objects.requireNonNull(command, "command");
        CampaignBudgetAccount account = accounts.computeIfAbsent(
                command.campaignId(),
                campaignId -> new CampaignBudgetAccount(campaignId, campaignCapacity, leaseCapacity)
        );
        return account.install(command, clock.instant());
    }

    @Override
    public PacingPosition positionOf(String campaignId) {
        Objects.requireNonNull(campaignId, "campaignId");
        CampaignBudgetAccount account = accounts.get(campaignId);
        return account == null ? new PacingPosition(false, 0L) : account.pacingPosition();
    }

    LeaseBalance balanceOf(String campaignId, String leaseId) {
        CampaignBudgetAccount account = accounts.get(campaignId);
        if (account == null) {
            throw new IllegalArgumentException("unknown campaign: " + campaignId);
        }
        return account.balanceOf(leaseId, clock.instant());
    }

    int availableInstancePermits() {
        return instanceCapacity.availablePermits();
    }

    private ReservationFinalization releaseCapacity(CampaignBudgetAccount.FinalizationOutcome outcome) {
        if (outcome.releasedGlobalCapacity()) {
            instanceCapacity.release();
        }
        return outcome.result();
    }
}
