package com.bbororo.rtb.dsp.budget;

import static com.bbororo.rtb.dsp.contract.ContractChecks.requireAfter;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requireNonBlank;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requireNonNegative;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requirePositive;

import java.time.Instant;
import java.util.Optional;
import java.util.Objects;

/** 로컬 예산 권한과 예약 상태를 바꾸는 메시지다. */
public final class BudgetMessages {

    private BudgetMessages() {
    }

    public record TryReserve(
            String auctionId,
            String impressionId,
            String bidId,
            String campaignId,
            long impressionAmountMicros,
            Instant reservedAt,
            Instant expiresAt
    ) {
        public TryReserve {
            auctionId = requireNonBlank(auctionId, "auctionId");
            impressionId = requireNonBlank(impressionId, "impressionId");
            bidId = requireNonBlank(bidId, "bidId");
            campaignId = requireNonBlank(campaignId, "campaignId");
            requirePositive(impressionAmountMicros, "impressionAmountMicros");
            requireAfter(reservedAt, expiresAt, "expiresAt");
        }
    }

    public sealed interface ReservationResult permits ReservationGranted, ReservationRejected {
    }

    public record ReservationGranted(
            String reservationId,
            String leaseId,
            String campaignId,
            String bidId,
            long impressionAmountMicros,
            Instant reservedAt,
            Instant expiresAt
    ) implements ReservationResult {
        public ReservationGranted {
            reservationId = requireNonBlank(reservationId, "reservationId");
            leaseId = requireNonBlank(leaseId, "leaseId");
            campaignId = requireNonBlank(campaignId, "campaignId");
            bidId = requireNonBlank(bidId, "bidId");
            requirePositive(impressionAmountMicros, "impressionAmountMicros");
            requireAfter(reservedAt, expiresAt, "expiresAt");
        }
    }

    public record ReservationRejected(ReservationRejection reason) implements ReservationResult {
        public ReservationRejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record ReservationReference(String campaignId, String leaseId, String reservationId) {
        public ReservationReference {
            campaignId = requireNonBlank(campaignId, "campaignId");
            leaseId = requireNonBlank(leaseId, "leaseId");
            reservationId = requireNonBlank(reservationId, "reservationId");
        }
    }

    public record ReleaseReservation(
            ReservationReference reservation,
            long impressionAmountMicros,
            String eventId,
            Instant occurredAt
    ) {
        public ReleaseReservation {
            Objects.requireNonNull(reservation, "reservation");
            requirePositive(impressionAmountMicros, "impressionAmountMicros");
            eventId = requireNonBlank(eventId, "eventId");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    public record CommitReservation(
            ReservationReference reservation,
            long impressionAmountMicros,
            String eventId,
            Instant occurredAt
    ) {
        public CommitReservation {
            Objects.requireNonNull(reservation, "reservation");
            requirePositive(impressionAmountMicros, "impressionAmountMicros");
            eventId = requireNonBlank(eventId, "eventId");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    public record ExpireReservation(
            ReservationReference reservation,
            long impressionAmountMicros,
            String eventId,
            Instant expiredAt
    ) {
        public ExpireReservation {
            Objects.requireNonNull(reservation, "reservation");
            requirePositive(impressionAmountMicros, "impressionAmountMicros");
            eventId = requireNonBlank(eventId, "eventId");
            Objects.requireNonNull(expiredAt, "expiredAt");
        }
    }

    public record ReservationExpiration(
            ReservationReference reservation,
            long impressionAmountMicros,
            Instant expiresAt
    ) {
        public ReservationExpiration {
            Objects.requireNonNull(reservation, "reservation");
            requirePositive(impressionAmountMicros, "impressionAmountMicros");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    public record InstallLease(
            String leaseId,
            String campaignId,
            long faceValueMicros,
            long generation,
            Instant issuedAt,
            Instant expiresAt
    ) {
        public InstallLease {
            leaseId = requireNonBlank(leaseId, "leaseId");
            campaignId = requireNonBlank(campaignId, "campaignId");
            requirePositive(faceValueMicros, "faceValueMicros");
            requirePositive(generation, "generation");
            requireAfter(issuedAt, expiresAt, "expiresAt");
        }
    }

    /** 리스 보충 정책이 읽는 캠페인별 결과적 일관성 투영이다. */
    public record LeaseSupplySnapshot(
            String campaignId,
            long reusableMicros,
            long reservedMicros,
            long committedMicros,
            long cumulativeReservedMicros,
            long cumulativeReleasedMicros,
            int openLeaseCount,
            Optional<Instant> earliestExpiry,
            Instant observedAt
    ) {
        public LeaseSupplySnapshot {
            campaignId = requireNonBlank(campaignId, "campaignId");
            requireNonNegative(reusableMicros, "reusableMicros");
            requireNonNegative(reservedMicros, "reservedMicros");
            requireNonNegative(committedMicros, "committedMicros");
            requireNonNegative(cumulativeReservedMicros, "cumulativeReservedMicros");
            requireNonNegative(cumulativeReleasedMicros, "cumulativeReleasedMicros");
            if (openLeaseCount < 0) {
                throw new IllegalArgumentException("openLeaseCount must not be negative");
            }
            earliestExpiry = Objects.requireNonNull(earliestExpiry, "earliestExpiry");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    public record PacingPosition(boolean hasUsableBudget, long lagPpm) {
    }

    public enum ReservationRejection {
        CONTENDED,
        NO_ACTIVE_LEASE,
        INSUFFICIENT_LOCAL_BUDGET,
        INSTANCE_CAPACITY_EXCEEDED,
        CAMPAIGN_CAPACITY_EXCEEDED,
        LEASE_EXPIRED,
        DUPLICATE_CONFLICT
    }

    public enum ReservationFinalization {
        APPLIED,
        ALREADY_APPLIED,
        ALREADY_FINALIZED_DIFFERENTLY,
        UNKNOWN_RESERVATION,
        NOT_DUE,
        TOO_LATE
    }

    public enum LeaseInstallResult {
        INSTALLED,
        ALREADY_INSTALLED,
        STALE_GENERATION,
        CAPACITY_EXCEEDED,
        EXPIRED,
        CONFLICT
    }

    public record LeaseBalance(
            long faceValueMicros,
            long availableMicros,
            long reservedMicros,
            long committedMicros,
            long quarantinedMicros
    ) {
        public LeaseBalance {
            requireNonNegative(faceValueMicros, "faceValueMicros");
            requireNonNegative(availableMicros, "availableMicros");
            requireNonNegative(reservedMicros, "reservedMicros");
            requireNonNegative(committedMicros, "committedMicros");
            requireNonNegative(quarantinedMicros, "quarantinedMicros");
            long classified = Math.addExact(
                    Math.addExact(availableMicros, reservedMicros),
                    Math.addExact(committedMicros, quarantinedMicros)
            );
            if (classified != faceValueMicros) {
                throw new IllegalArgumentException("lease balance must preserve face value");
            }
        }
    }
}
