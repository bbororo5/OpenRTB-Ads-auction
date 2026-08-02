package com.bbororo.rtb.dsp.budget;

import static com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseInstallResult.ALREADY_INSTALLED;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseInstallResult.CAPACITY_EXCEEDED;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseInstallResult.CONFLICT;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseInstallResult.EXPIRED;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseInstallResult.INSTALLED;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseInstallResult.STALE_GENERATION;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationFinalization.ALREADY_APPLIED;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationFinalization.ALREADY_FINALIZED_DIFFERENTLY;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationFinalization.APPLIED;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationFinalization.NOT_DUE;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationFinalization.TOO_LATE;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationFinalization.UNKNOWN_RESERVATION;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationRejection.CAMPAIGN_CAPACITY_EXCEEDED;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationRejection.CONTENDED;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationRejection.DUPLICATE_CONFLICT;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationRejection.INSUFFICIENT_LOCAL_BUDGET;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationRejection.LEASE_EXPIRED;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationRejection.NO_ACTIVE_LEASE;

import com.bbororo.rtb.dsp.budget.BudgetMessages.CommitReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ExpireReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.InstallLease;
import com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseBalance;
import com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseInstallResult;
import com.bbororo.rtb.dsp.budget.BudgetMessages.PacingPosition;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReleaseReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationExpiration;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationFinalization;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationGranted;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationReference;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationRejected;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationResult;
import com.bbororo.rtb.dsp.budget.BudgetMessages.TryReserve;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** 한 캠페인의 리스와 예약 금액을 하나의 원자 경계에서 변경한다. */
final class CampaignBudgetAccount {

    private static final Comparator<LeaseAccount> LEASE_USE_ORDER = Comparator
            .comparing(LeaseAccount::expiresAt)
            .thenComparingLong(LeaseAccount::generation)
            .thenComparing(LeaseAccount::leaseId);

    private final String campaignId;
    private final int maxOutstandingReservations;
    private final int maxLeases;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, LeaseAccount> leases = new HashMap<>();

    private long lastInstalledGeneration;
    private int outstandingReservations;
    private volatile PacingPosition pacingPosition = new PacingPosition(false, 0L);

    CampaignBudgetAccount(String campaignId, int maxOutstandingReservations, int maxLeases) {
        this.campaignId = Objects.requireNonNull(campaignId, "campaignId");
        this.maxOutstandingReservations = maxOutstandingReservations;
        this.maxLeases = maxLeases;
    }

    LeaseInstallResult install(InstallLease command, Instant now) {
        lock.lock();
        try {
            LeaseAccount existing = leases.get(command.leaseId());
            if (existing != null) {
                return existing.matches(command) ? ALREADY_INSTALLED : CONFLICT;
            }
            if (command.generation() <= lastInstalledGeneration) {
                return STALE_GENERATION;
            }
            if (leases.size() >= maxLeases) {
                return CAPACITY_EXCEEDED;
            }
            if (!now.isBefore(command.expiresAt())) {
                return EXPIRED;
            }

            leases.put(command.leaseId(), LeaseAccount.install(command, now));
            lastInstalledGeneration = command.generation();
            refreshAndPublish(now);
            return INSTALLED;
        } finally {
            lock.unlock();
        }
    }

    ReservationResult tryReserve(
            TryReserve command,
            Instant now,
            Supplier<String> reservationIds,
            ReservationExpirationSink expirationSink
    ) {
        if (!lock.tryLock()) {
            return new ReservationRejected(CONTENDED);
        }
        try {
            refreshAndPublish(now);
            if (!now.isBefore(command.expiresAt())) {
                return new ReservationRejected(LEASE_EXPIRED);
            }
            if (outstandingReservations >= maxOutstandingReservations) {
                return new ReservationRejected(CAMPAIGN_CAPACITY_EXCEEDED);
            }

            Optional<LeaseAccount> selected = leases.values().stream()
                    .filter(lease -> lease.canReserve(command.impressionAmountMicros(), now))
                    .min(LEASE_USE_ORDER);
            if (selected.isEmpty()) {
                return new ReservationRejected(rejectionWithoutUsableLease(now));
            }

            String reservationId = Objects.requireNonNull(reservationIds.get(), "reservationId");
            if (reservationId.isBlank() || containsReservation(reservationId)) {
                return new ReservationRejected(DUPLICATE_CONFLICT);
            }

            LeaseAccount lease = selected.orElseThrow();
            Reservation reservation = lease.reserve(reservationId, command);
            outstandingReservations++;
            var expiration = new ReservationExpiration(
                    new ReservationReference(campaignId, lease.leaseId(), reservationId),
                    reservation.amountMicros(),
                    reservation.expiresAt()
            );
            try {
                expirationSink.schedule(expiration);
            } catch (RuntimeException failure) {
                lease.rollbackReservation(reservationId);
                outstandingReservations--;
                refreshAndPublish(now);
                throw failure;
            }
            refreshAndPublish(now);
            return new ReservationGranted(
                    reservationId,
                    lease.leaseId(),
                    campaignId,
                    command.bidId(),
                    command.impressionAmountMicros(),
                    command.reservedAt(),
                    command.expiresAt()
            );
        } finally {
            lock.unlock();
        }
    }

    FinalizationOutcome release(ReleaseReservation command) {
        return finalizeReservation(
                command.reservation(),
                command.impressionAmountMicros(),
                command.eventId(),
                command.occurredAt(),
                ReservationState.RELEASED
        );
    }

    FinalizationOutcome commit(CommitReservation command) {
        return finalizeReservation(
                command.reservation(),
                command.impressionAmountMicros(),
                command.eventId(),
                command.occurredAt(),
                ReservationState.COMMITTED
        );
    }

    FinalizationOutcome expire(ExpireReservation command) {
        return finalizeReservation(
                command.reservation(),
                command.impressionAmountMicros(),
                command.eventId(),
                command.expiredAt(),
                ReservationState.EXPIRED
        );
    }

    PacingPosition pacingPosition() {
        return pacingPosition;
    }

    LeaseBalance balanceOf(String leaseId, Instant now) {
        lock.lock();
        try {
            refreshAndPublish(now);
            LeaseAccount lease = leases.get(leaseId);
            if (lease == null) {
                throw new IllegalArgumentException("unknown lease: " + leaseId);
            }
            return lease.balance();
        } finally {
            lock.unlock();
        }
    }

    private FinalizationOutcome finalizeReservation(
            ReservationReference reference,
            long amountMicros,
            String eventId,
            Instant occurredAt,
            ReservationState targetState
    ) {
        lock.lock();
        try {
            LeaseAccount lease = leases.get(reference.leaseId());
            if (lease == null) {
                return FinalizationOutcome.notApplied(UNKNOWN_RESERVATION);
            }
            Reservation reservation = lease.reservations.get(reference.reservationId());
            if (reservation == null) {
                return FinalizationOutcome.notApplied(UNKNOWN_RESERVATION);
            }
            if (reservation.amountMicros() != amountMicros) {
                return FinalizationOutcome.notApplied(ALREADY_FINALIZED_DIFFERENTLY);
            }
            if (targetState == ReservationState.EXPIRED && occurredAt.isBefore(reservation.expiresAt())) {
                return FinalizationOutcome.notApplied(NOT_DUE);
            }
            if (targetState != ReservationState.EXPIRED && occurredAt.isAfter(reservation.expiresAt())) {
                return FinalizationOutcome.notApplied(TOO_LATE);
            }
            if (reservation.state() == targetState) {
                return FinalizationOutcome.notApplied(ALREADY_APPLIED);
            }
            if (reservation.state() != ReservationState.RESERVED) {
                return FinalizationOutcome.notApplied(ALREADY_FINALIZED_DIFFERENTLY);
            }

            lease.finalizeReservation(reservation, targetState, eventId);
            outstandingReservations--;
            refreshAndPublish(occurredAt);
            return FinalizationOutcome.applied();
        } finally {
            lock.unlock();
        }
    }

    private BudgetMessages.ReservationRejection rejectionWithoutUsableLease(Instant now) {
        boolean hasOpenLease = leases.values().stream().anyMatch(lease -> lease.isOpen(now));
        if (hasOpenLease) {
            return INSUFFICIENT_LOCAL_BUDGET;
        }
        boolean hasExpiredLease = leases.values().stream()
                .anyMatch(lease -> !now.isBefore(lease.expiresAt()));
        return hasExpiredLease ? LEASE_EXPIRED : NO_ACTIVE_LEASE;
    }

    private boolean containsReservation(String reservationId) {
        return leases.values().stream()
                .anyMatch(lease -> lease.reservations.containsKey(reservationId));
    }

    private void refreshAndPublish(Instant now) {
        leases.values().forEach(lease -> lease.refreshState(now));
        boolean usable = leases.values().stream()
                .anyMatch(lease -> lease.isOpen(now) && lease.unusedMicros > 0L);
        pacingPosition = new PacingPosition(usable, 0L);
    }

    record FinalizationOutcome(ReservationFinalization result, boolean releasedGlobalCapacity) {

        static FinalizationOutcome applied() {
            return new FinalizationOutcome(APPLIED, true);
        }

        static FinalizationOutcome notApplied(ReservationFinalization result) {
            return new FinalizationOutcome(result, false);
        }
    }

    private enum LeaseState {
        PENDING,
        OPEN,
        DRAINING
    }

    private enum ReservationState {
        RESERVED,
        RELEASED,
        COMMITTED,
        EXPIRED
    }

    private static final class LeaseAccount {

        private final InstallLease installedLease;
        private final Map<String, Reservation> reservations = new HashMap<>();
        private LeaseState state;
        private long unusedMicros;
        private long reservedMicros;
        private long committedMicros;

        private LeaseAccount(InstallLease installedLease, LeaseState state) {
            this.installedLease = installedLease;
            this.state = state;
            this.unusedMicros = installedLease.faceValueMicros();
        }

        static LeaseAccount install(InstallLease command, Instant now) {
            LeaseState state = now.isBefore(command.startsAt()) ? LeaseState.PENDING : LeaseState.OPEN;
            return new LeaseAccount(command, state);
        }

        String leaseId() {
            return installedLease.leaseId();
        }

        long generation() {
            return installedLease.generation();
        }

        Instant expiresAt() {
            return installedLease.expiresAt();
        }

        boolean matches(InstallLease other) {
            return installedLease.equals(other);
        }

        boolean isOpen(Instant now) {
            refreshState(now);
            return state == LeaseState.OPEN;
        }

        boolean canReserve(long amountMicros, Instant now) {
            return isOpen(now) && unusedMicros >= amountMicros;
        }

        void refreshState(Instant now) {
            if (!now.isBefore(installedLease.expiresAt())) {
                state = LeaseState.DRAINING;
            } else if (state == LeaseState.PENDING && !now.isBefore(installedLease.startsAt())) {
                state = LeaseState.OPEN;
            }
        }

        Reservation reserve(String reservationId, TryReserve command) {
            unusedMicros = Math.subtractExact(unusedMicros, command.impressionAmountMicros());
            reservedMicros = Math.addExact(reservedMicros, command.impressionAmountMicros());
            Reservation reservation = new Reservation(
                    reservationId,
                    command.bidId(),
                    command.impressionAmountMicros(),
                    command.reservedAt(),
                    command.expiresAt(),
                    ReservationState.RESERVED,
                    null
            );
            reservations.put(reservationId, reservation);
            assertInvariant();
            return reservation;
        }

        void rollbackReservation(String reservationId) {
            Reservation reservation = reservations.remove(reservationId);
            reservedMicros = Math.subtractExact(reservedMicros, reservation.amountMicros());
            unusedMicros = Math.addExact(unusedMicros, reservation.amountMicros());
            assertInvariant();
        }

        void finalizeReservation(Reservation reservation, ReservationState targetState, String eventId) {
            reservedMicros = Math.subtractExact(reservedMicros, reservation.amountMicros());
            if (targetState == ReservationState.COMMITTED) {
                committedMicros = Math.addExact(committedMicros, reservation.amountMicros());
            } else {
                unusedMicros = Math.addExact(unusedMicros, reservation.amountMicros());
            }
            reservations.put(reservation.reservationId(), reservation.finalizeAs(targetState, eventId));
            assertInvariant();
        }

        LeaseBalance balance() {
            return new LeaseBalance(
                    installedLease.faceValueMicros(),
                    unusedMicros,
                    reservedMicros,
                    committedMicros,
                    0L
            );
        }

        private void assertInvariant() {
            long classified = Math.addExact(unusedMicros, Math.addExact(reservedMicros, committedMicros));
            if (classified != installedLease.faceValueMicros()) {
                throw new IllegalStateException("lease face value was not preserved");
            }
        }
    }

    private record Reservation(
            String reservationId,
            String bidId,
            long amountMicros,
            Instant reservedAt,
            Instant expiresAt,
            ReservationState state,
            String terminalEventId
    ) {

        Reservation finalizeAs(ReservationState targetState, String eventId) {
            return new Reservation(
                    reservationId,
                    bidId,
                    amountMicros,
                    reservedAt,
                    expiresAt,
                    targetState,
                    eventId
            );
        }
    }
}
