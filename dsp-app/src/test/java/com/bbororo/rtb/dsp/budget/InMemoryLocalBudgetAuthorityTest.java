package com.bbororo.rtb.dsp.budget;

import static com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseInstallResult.INSTALLED;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationFinalization.ALREADY_APPLIED;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationFinalization.ALREADY_FINALIZED_DIFFERENTLY;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationFinalization.APPLIED;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationRejection.INSTANCE_CAPACITY_EXCEEDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.dsp.budget.BudgetMessages.CommitReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ExpireReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.InstallLease;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReleaseReservation;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationFinalization;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationGranted;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationReference;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationRejected;
import com.bbororo.rtb.dsp.budget.BudgetMessages.ReservationResult;
import com.bbororo.rtb.dsp.budget.BudgetMessages.TryReserve;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InMemoryLocalBudgetAuthorityTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void reusesReleasedMoneyFromTheOldestOpenLease() {
        TestFixture fixture = fixture(100, 100);
        assertEquals(INSTALLED, fixture.authority.install(lease("lease-1", 1, 100, 10)));
        assertEquals(INSTALLED, fixture.authority.install(lease("lease-2", 2, 100, 20)));

        ReservationGranted first = granted(fixture.authority.tryReserve(reserve("bid-1", 100)));
        assertEquals("lease-1", first.leaseId());

        var reference = new ReservationReference("campaign-1", first.leaseId(), first.reservationId());
        assertEquals(APPLIED, fixture.authority.release(
                new ReleaseReservation(reference, 100, "event-loss-1", NOW.plusSeconds(1))
        ));

        ReservationGranted second = granted(fixture.authority.tryReserve(reserve("bid-2", 100)));
        assertEquals("lease-1", second.leaseId());
        assertEquals(0, fixture.authority.balanceOf("campaign-1", "lease-1").availableMicros());
        assertEquals(100, fixture.authority.balanceOf("campaign-1", "lease-1").reservedMicros());
    }

    @Test
    void concurrentReservationsNeverExceedTheLeaseFaceValue() throws Exception {
        TestFixture fixture = fixture(100, 100);
        fixture.authority.install(lease("lease-1", 1, 100, 10));
        int contenders = 32;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<ReservationResult> results = new ConcurrentLinkedQueue<>();

        try (var executor = Executors.newFixedThreadPool(contenders)) {
            for (int index = 0; index < contenders; index++) {
                int bid = index;
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    results.add(fixture.authority.tryReserve(reserve("bid-" + bid, 100)));
                    return null;
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        long granted = results.stream().filter(ReservationGranted.class::isInstance).count();
        assertEquals(1, granted);
        assertEquals(0, fixture.authority.balanceOf("campaign-1", "lease-1").availableMicros());
        assertEquals(100, fixture.authority.balanceOf("campaign-1", "lease-1").reservedMicros());
    }

    @Test
    void commitAndExpiryRaceProducesOneMonetaryEffect() throws Exception {
        TestFixture fixture = fixture(100, 100);
        fixture.authority.install(lease("lease-1", 1, 100, 10));
        ReservationGranted grant = granted(fixture.authority.tryReserve(reserve("bid-1", 100)));
        var reference = new ReservationReference("campaign-1", grant.leaseId(), grant.reservationId());
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<ReservationFinalization> results = new ConcurrentLinkedQueue<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            executor.submit(() -> {
                start.await();
                results.add(fixture.authority.commit(
                        new CommitReservation(reference, 100, "event-bill", NOW.plusSeconds(1))
                ));
                return null;
            });
            executor.submit(() -> {
                start.await();
                results.add(fixture.authority.expire(
                        new ExpireReservation(reference, 100, "event-expire", NOW.plusSeconds(5))
                ));
                return null;
            });
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(Set.of(APPLIED, ALREADY_FINALIZED_DIFFERENTLY), Set.copyOf(results));
        var balance = fixture.authority.balanceOf("campaign-1", "lease-1");
        assertEquals(100, balance.availableMicros() + balance.committedMicros());
        assertEquals(0, balance.reservedMicros());
    }

    @Test
    void finalizationIsIdempotentAndReleasesInstanceCapacityOnce() {
        TestFixture fixture = fixture(1, 10);
        fixture.authority.install(lease("lease-1", 1, 200, 10));
        ReservationGranted grant = granted(fixture.authority.tryReserve(reserve("bid-1", 100)));
        assertRejected(fixture.authority.tryReserve(reserve("bid-2", 100)), INSTANCE_CAPACITY_EXCEEDED);
        var release = new ReleaseReservation(
                new ReservationReference("campaign-1", grant.leaseId(), grant.reservationId()),
                100,
                "event-loss",
                NOW.plusSeconds(1)
        );

        assertEquals(APPLIED, fixture.authority.release(release));
        assertEquals(ALREADY_APPLIED, fixture.authority.release(release));
        assertEquals(1, fixture.authority.availableInstancePermits());
        assertInstanceOf(ReservationGranted.class, fixture.authority.tryReserve(reserve("bid-2", 100)));
    }

    @Test
    void schedulesEveryGrantedReservationWithItsFullIdentity() {
        TestFixture fixture = fixture(10, 10);
        fixture.authority.install(lease("lease-1", 1, 100, 10));

        ReservationGranted grant = granted(fixture.authority.tryReserve(reserve("bid-1", 100)));

        assertEquals(1, fixture.expirations.size());
        var expiration = fixture.expirations.getFirst();
        assertEquals("campaign-1", expiration.reservation().campaignId());
        assertEquals(grant.leaseId(), expiration.reservation().leaseId());
        assertEquals(grant.reservationId(), expiration.reservation().reservationId());
    }

    private static TestFixture fixture(int instanceCapacity, int campaignCapacity) {
        AtomicInteger ids = new AtomicInteger();
        List<BudgetMessages.ReservationExpiration> expirations = new ArrayList<>();
        var authority = new InMemoryLocalBudgetAuthority(
                instanceCapacity,
                campaignCapacity,
                16,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "reservation-" + ids.incrementAndGet(),
                expirations::add
        );
        return new TestFixture(authority, expirations);
    }

    private static InstallLease lease(String leaseId, long generation, long amount, long expiresInSeconds) {
        return new InstallLease(
                leaseId,
                "campaign-1",
                amount,
                generation,
                NOW.minusSeconds(1),
                NOW.plusSeconds(expiresInSeconds)
        );
    }

    private static TryReserve reserve(String bidId, long amount) {
        return new TryReserve(
                "auction-1",
                "imp-" + bidId,
                bidId,
                "campaign-1",
                amount,
                NOW,
                NOW.plusSeconds(5)
        );
    }

    private static ReservationGranted granted(ReservationResult result) {
        return assertInstanceOf(ReservationGranted.class, result);
    }

    private static void assertRejected(
            ReservationResult result,
            BudgetMessages.ReservationRejection reason
    ) {
        assertEquals(reason, assertInstanceOf(ReservationRejected.class, result).reason());
    }

    private record TestFixture(
            InMemoryLocalBudgetAuthority authority,
            List<BudgetMessages.ReservationExpiration> expirations
    ) {
    }
}
