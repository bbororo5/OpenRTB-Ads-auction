package com.bbororo.rtb.ssp.claim;

import com.bbororo.rtb.ssp.contract.SspMessages.BillingClaim;
import com.bbororo.rtb.ssp.contract.SspMessages.BillingDeliveryTask;
import com.bbororo.rtb.ssp.contract.SspMessages.DeliveryLease;
import com.bbororo.rtb.ssp.contract.SspMessages.DeliveryOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.LeasedBillingDelivery;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderAcceptance;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 3단계 조립 시험용 청구 저장소다.
 *
 * <p>하나의 임계 구역에서 청구 근거와 전달 작업을 함께 만든다. PostgreSQL 어댑터는 같은 원자성을
 * 하나의 DB 트랜잭션으로 대체한다.</p>
 */
public final class InMemoryClaimDeliveryStore implements ClaimDeliveryStore {

    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(1);

    private final Duration leaseDuration;
    private final Map<String, StoredDelivery> deliveriesByProofDigest = new HashMap<>();

    public InMemoryClaimDeliveryStore() {
        this(DEFAULT_LEASE_DURATION);
    }

    public InMemoryClaimDeliveryStore(Duration leaseDuration) {
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.leaseDuration = leaseDuration;
    }

    @Override
    public synchronized RenderAcceptance recordClaimAndScheduleDelivery(BillingClaim claim) {
        Objects.requireNonNull(claim);
        if (deliveriesByProofDigest.containsKey(claim.proofDigest())) {
            return RenderAcceptance.DUPLICATE;
        }
        BillingDeliveryTask task = new BillingDeliveryTask(UUID.randomUUID().toString(), claim);
        deliveriesByProofDigest.put(claim.proofDigest(), new StoredDelivery(task));
        return RenderAcceptance.ACCEPTED;
    }

    @Override
    public synchronized Optional<LeasedBillingDelivery> leaseDueDelivery(Instant now) {
        Objects.requireNonNull(now);
        for (StoredDelivery delivery : deliveriesByProofDigest.values()) {
            if (!delivery.canLeaseAt(now)) {
                continue;
            }
            return Optional.of(delivery.lease(now, leaseDuration));
        }
        return Optional.empty();
    }

    @Override
    public synchronized void completeOrReleaseDelivery(DeliveryLease lease, DeliveryOutcome outcome, Instant now) {
        Objects.requireNonNull(lease);
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(now);
        for (StoredDelivery delivery : deliveriesByProofDigest.values()) {
            if (delivery.matches(lease)) {
                delivery.finish(outcome, now);
                return;
            }
        }
    }

    /** 시험용 어댑터가 현재 보존한 청구 수다. */
    public synchronized int recordedClaimCount() {
        return deliveriesByProofDigest.size();
    }

    /** 아직 전달 완료·만료로 종결되지 않은 작업 수다. */
    public synchronized int pendingDeliveryCount() {
        return (int) deliveriesByProofDigest.values().stream()
                .filter(StoredDelivery::isPending)
                .count();
    }

    private static final class StoredDelivery {

        private final BillingDeliveryTask task;
        private DeliveryLease lease;
        private DeliveryState state = DeliveryState.PENDING;

        private StoredDelivery(BillingDeliveryTask task) {
            this.task = task;
        }

        private boolean canLeaseAt(Instant now) {
            if (state == DeliveryState.DELIVERED || state == DeliveryState.UNDELIVERED) {
                return false;
            }
            if (!now.isBefore(task.claim().billingDeadline())) {
                state = DeliveryState.UNDELIVERED;
                return false;
            }
            return state == DeliveryState.PENDING || !now.isBefore(lease.leaseUntil());
        }

        private boolean isPending() {
            return state == DeliveryState.PENDING || state == DeliveryState.LEASED;
        }

        private LeasedBillingDelivery lease(Instant now, Duration duration) {
            long nextGeneration = lease == null ? 1 : lease.generation() + 1;
            lease = new DeliveryLease(task.deliveryId(), nextGeneration, now.plus(duration));
            state = DeliveryState.LEASED;
            return new LeasedBillingDelivery(task, lease);
        }

        private boolean matches(DeliveryLease candidate) {
            return lease != null
                    && lease.deliveryId().equals(candidate.deliveryId())
                    && lease.generation() == candidate.generation();
        }

        private void finish(DeliveryOutcome outcome, Instant now) {
            if (outcome == DeliveryOutcome.DELIVERED) {
                state = DeliveryState.DELIVERED;
            } else if (outcome == DeliveryOutcome.RETRY && now.isBefore(task.claim().billingDeadline())) {
                state = DeliveryState.PENDING;
            } else {
                state = DeliveryState.UNDELIVERED;
            }
        }
    }

    private enum DeliveryState {
        PENDING,
        LEASED,
        DELIVERED,
        UNDELIVERED
    }
}
