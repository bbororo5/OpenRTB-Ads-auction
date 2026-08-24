package com.bbororo.rtb.dsp.spending.internal;

import com.bbororo.rtb.dsp.spending.api.CampaignPacingView;
import com.bbororo.rtb.dsp.spending.api.LeaseInstaller;
import com.bbororo.rtb.dsp.spending.api.LocalLeaseSupplyView;
import com.bbororo.rtb.dsp.spending.api.ReservationAuthority;
import com.bbororo.rtb.dsp.spending.api.ReservationFinalizer;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.CommitReservation;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ExpireReservation;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.InstallLease;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.LeaseBalance;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.LeaseInstallResult;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.LeaseSupplySnapshot;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.PacingPosition;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReleaseReservation;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationFinalization;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationRejected;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationResult;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.TryReserve;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.function.LongSupplier;

/** 캠페인별 잠금과 인스턴스 용량 상한을 사용하는 로컬 예산 권한 구현이다. */
public final class InMemoryLocalSpendingAuthority implements
        ReservationAuthority,
        ReservationFinalizer,
        LeaseInstaller,
        CampaignPacingView,
        LocalLeaseSupplyView {

    public static final int DEFAULT_INSTANCE_CAPACITY = 20_000;
    public static final int DEFAULT_CAMPAIGN_CAPACITY = 2_500;
    public static final int DEFAULT_LEASE_CAPACITY = 64;
    public static final Duration DEFAULT_LEASE_SAFETY_MARGIN = Duration.ofMillis(100);

    private final ConcurrentHashMap<String, CampaignSpendingAccount> accounts = new ConcurrentHashMap<>();
    private final Semaphore instanceCapacity;
    private final int campaignCapacity;
    private final int leaseCapacity;
    private final Clock clock;
    private final LongSupplier monotonicNanos;
    private final Duration leaseSafetyMargin;
    private final Supplier<String> reservationIds;
    private final ReservationExpirationSink expirationSink;

    public InMemoryLocalSpendingAuthority(ReservationExpirationSink expirationSink) {
        this(
                DEFAULT_INSTANCE_CAPACITY,
                DEFAULT_CAMPAIGN_CAPACITY,
                DEFAULT_LEASE_CAPACITY,
                Clock.systemUTC(),
                System::nanoTime,
                DEFAULT_LEASE_SAFETY_MARGIN,
                () -> UUID.randomUUID().toString(),
                expirationSink
        );
    }

    InMemoryLocalSpendingAuthority(
            int instanceCapacity,
            int campaignCapacity,
            int leaseCapacity,
            Clock clock,
            Supplier<String> reservationIds,
            ReservationExpirationSink expirationSink
    ) {
        this(
                instanceCapacity,
                campaignCapacity,
                leaseCapacity,
                clock,
                System::nanoTime,
                Duration.ZERO,
                reservationIds,
                expirationSink
        );
    }

    InMemoryLocalSpendingAuthority(
            int instanceCapacity,
            int campaignCapacity,
            int leaseCapacity,
            Clock clock,
            LongSupplier monotonicNanos,
            Duration leaseSafetyMargin,
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
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        this.leaseSafetyMargin = Objects.requireNonNull(leaseSafetyMargin, "leaseSafetyMargin");
        if (leaseSafetyMargin.isNegative()) {
            throw new IllegalArgumentException("leaseSafetyMargin must not be negative");
        }
        this.reservationIds = Objects.requireNonNull(reservationIds, "reservationIds");
        this.expirationSink = Objects.requireNonNull(expirationSink, "expirationSink");
    }

    @Override
    public ReservationResult tryReserve(TryReserve command) {
        Objects.requireNonNull(command, "command");
        if (!instanceCapacity.tryAcquire()) {
            return new ReservationRejected(SpendingMessages.ReservationRejection.INSTANCE_CAPACITY_EXCEEDED);
        }

        CampaignSpendingAccount account = accounts.get(command.campaignId());
        if (account == null) {
            instanceCapacity.release();
            return new ReservationRejected(SpendingMessages.ReservationRejection.NO_ACTIVE_LEASE);
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
        CampaignSpendingAccount account = accounts.get(command.reservation().campaignId());
        if (account == null) {
            return ReservationFinalization.UNKNOWN_RESERVATION;
        }
        return releaseCapacity(account.release(command));
    }

    @Override
    public ReservationFinalization commit(CommitReservation command) {
        Objects.requireNonNull(command, "command");
        CampaignSpendingAccount account = accounts.get(command.reservation().campaignId());
        if (account == null) {
            return ReservationFinalization.UNKNOWN_RESERVATION;
        }
        return releaseCapacity(account.commit(command));
    }

    @Override
    public ReservationFinalization expire(ExpireReservation command) {
        Objects.requireNonNull(command, "command");
        CampaignSpendingAccount account = accounts.get(command.reservation().campaignId());
        if (account == null) {
            return ReservationFinalization.UNKNOWN_RESERVATION;
        }
        return releaseCapacity(account.expire(command));
    }

    @Override
    public LeaseInstallResult install(InstallLease command, long requestStartedNanos) {
        Objects.requireNonNull(command, "command");
        CampaignSpendingAccount account = accounts.computeIfAbsent(
                command.campaignId(),
                campaignId -> new CampaignSpendingAccount(
                        campaignId,
                        campaignCapacity,
                        leaseCapacity,
                        monotonicNanos,
                        leaseSafetyMargin
                )
        );
        return account.install(command, requestStartedNanos);
    }

    @Override
    public PacingPosition positionOf(String campaignId) {
        Objects.requireNonNull(campaignId, "campaignId");
        CampaignSpendingAccount account = accounts.get(campaignId);
        return account == null ? new PacingPosition(false, 0L) : account.pacingPosition();
    }

    @Override
    public List<LeaseSupplySnapshot> supplySnapshots() {
        return accounts.values().stream()
                .map(CampaignSpendingAccount::supplySnapshot)
                .sorted(Comparator.comparing(LeaseSupplySnapshot::campaignId))
                .toList();
    }

    LeaseBalance balanceOf(String campaignId, String leaseId) {
        CampaignSpendingAccount account = accounts.get(campaignId);
        if (account == null) {
            throw new IllegalArgumentException("unknown campaign: " + campaignId);
        }
        return account.balanceOf(leaseId, clock.instant());
    }

    int availableInstancePermits() {
        return instanceCapacity.availablePermits();
    }

    private ReservationFinalization releaseCapacity(CampaignSpendingAccount.FinalizationOutcome outcome) {
        if (outcome.releasedGlobalCapacity()) {
            instanceCapacity.release();
        }
        return outcome.result();
    }
}
