package com.bbororo.rtb.ssp.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.ssp.contract.SspMessages.BillingClaim;
import com.bbororo.rtb.ssp.contract.SspMessages.DeliveryOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderAcceptance;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("ssp-claim-store")
class PostgreSqlClaimDeliveryStoreIntegrationTest {

    @Test
    void persistsDeduplicatesLeasesAndCompletes() throws Exception {
        try (HikariDataSource dataSource = dataSource()) {
            truncate(dataSource);
            var store = new PostgreSqlClaimDeliveryStore(dataSource, Duration.ofSeconds(1));
            store.verifyReady();
            assertEquals(0, rowCount(dataSource));
            Instant now = Instant.now();
            BillingClaim claim = claim("a".repeat(64), now);

            assertEquals(RenderAcceptance.ACCEPTED, store.recordClaimAndScheduleDelivery(claim));
            assertEquals(RenderAcceptance.DUPLICATE, store.recordClaimAndScheduleDelivery(claim));
            BillingClaim conflictingClaim = new BillingClaim(
                    "provider-1", "request-1", "imp-1", "auction-1/imp-1",
                    "b".repeat(64), "project-dsp", 2_000,
                    URI.create("https://project-dsp.test/burl/1"), now.plusSeconds(5)
            );
            assertEquals(RenderAcceptance.REJECTED, store.recordClaimAndScheduleDelivery(conflictingClaim));
            var leased = store.leaseDueDelivery(Instant.now()).orElseThrow();
            assertEquals(2_000L, leased.task().claim().cpmMilliKrw());

            Instant completedAt = Instant.now();
            store.completeOrReleaseDelivery(leased.lease(), DeliveryOutcome.DELIVERED, completedAt);
            assertTrue(store.leaseDueDelivery(Instant.now()).isEmpty());
        }
    }

    @Test
    void serializesConcurrentCopiesIntoOneClaimAndOneDelivery() throws Exception {
        try (HikariDataSource dataSource = dataSource();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            truncate(dataSource);
            var store = new PostgreSqlClaimDeliveryStore(dataSource, Duration.ofSeconds(1));
            BillingClaim claim = claim("a".repeat(64), Instant.now());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<RenderAcceptance>> attempts = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                attempts.add(executor.submit(() -> {
                    start.await();
                    return store.recordClaimAndScheduleDelivery(claim);
                }));
            }

            start.countDown();
            List<RenderAcceptance> results = new ArrayList<>();
            for (Future<RenderAcceptance> attempt : attempts) {
                results.add(attempt.get());
            }

            assertEquals(1, results.stream().filter(RenderAcceptance.ACCEPTED::equals).count());
            assertEquals(31, results.stream().filter(RenderAcceptance.DUPLICATE::equals).count());
            assertEquals(1, rowCount(dataSource));
            assertTrue(store.leaseDueDelivery(Instant.now()).isPresent());
        }
    }

    @Test
    void keepsTheFirstProofWhenDifferentProofsRaceForOneSlot() throws Exception {
        try (HikariDataSource dataSource = dataSource();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            truncate(dataSource);
            var store = new PostgreSqlClaimDeliveryStore(dataSource, Duration.ofSeconds(1));
            Instant now = Instant.now();
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ClaimAttempt>> attempts = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                String digest = (index % 2 == 0 ? "a" : "b").repeat(64);
                attempts.add(executor.submit(() -> {
                    start.await();
                    return new ClaimAttempt(
                            digest,
                            store.recordClaimAndScheduleDelivery(claim(digest, now))
                    );
                }));
            }

            start.countDown();
            List<ClaimAttempt> results = new ArrayList<>();
            for (Future<ClaimAttempt> attempt : attempts) {
                results.add(attempt.get());
            }
            String storedDigest = storedProofDigest(dataSource);

            assertEquals(1, results.stream()
                    .filter(attempt -> attempt.result() == RenderAcceptance.ACCEPTED)
                    .count());
            assertEquals(15, results.stream()
                    .filter(attempt -> attempt.digest().equals(storedDigest))
                    .filter(attempt -> attempt.result() == RenderAcceptance.DUPLICATE)
                    .count());
            assertEquals(16, results.stream()
                    .filter(attempt -> !attempt.digest().equals(storedDigest))
                    .filter(attempt -> attempt.result() == RenderAcceptance.REJECTED)
                    .count());
            assertEquals(1, rowCount(dataSource));
        }
    }

    @Test
    void delaysRetryAndRejectsAStaleWorkerCompletion() throws Exception {
        try (HikariDataSource dataSource = dataSource()) {
            truncate(dataSource);
            var store = new PostgreSqlClaimDeliveryStore(
                    dataSource,
                    Duration.ofMillis(100),
                    new DeliveryRetryPolicy(Duration.ofMillis(50), Duration.ofMillis(500))
            );
            Instant now = Instant.now();
            assertEquals(
                    RenderAcceptance.ACCEPTED,
                    store.recordClaimAndScheduleDelivery(claim("a".repeat(64), now))
            );

            Instant recordedAt = Instant.now();
            var first = store.leaseDueDelivery(recordedAt.plusMillis(10)).orElseThrow();
            assertEquals(
                    java.util.Optional.of(recordedAt.plusMillis(70)),
                    store.completeOrReleaseDelivery(
                            first.lease(), DeliveryOutcome.RETRY, recordedAt.plusMillis(20)
                    )
            );
            assertTrue(store.leaseDueDelivery(recordedAt.plusMillis(69)).isEmpty());

            var second = store.leaseDueDelivery(recordedAt.plusMillis(70)).orElseThrow();
            store.completeOrReleaseDelivery(first.lease(), DeliveryOutcome.DELIVERED, recordedAt.plusMillis(80));
            assertEquals("LEASED", deliveryState(dataSource));

            store.completeOrReleaseDelivery(second.lease(), DeliveryOutcome.DELIVERED, recordedAt.plusMillis(90));
            assertEquals("DELIVERED", deliveryState(dataSource));
            assertTrue(store.leaseDueDelivery(recordedAt.plusMillis(100)).isEmpty());
        }
    }

    private static BillingClaim claim(String proofDigest, Instant now) {
        return new BillingClaim(
                "provider-1", "request-1", "imp-1", "auction-1/imp-1",
                proofDigest, "project-dsp", 2_000,
                URI.create("https://project-dsp.test/burl/1"), now.plusSeconds(5)
        );
    }

    private static void truncate(HikariDataSource dataSource) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate("TRUNCATE TABLE ssp_billing_delivery");
        }
    }

    private static int rowCount(HikariDataSource dataSource) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM ssp_billing_delivery")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String storedProofDigest(HikariDataSource dataSource) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT proof_digest FROM ssp_billing_delivery"
             )) {
            result.next();
            return result.getString(1).trim();
        }
    }

    private static String deliveryState(HikariDataSource dataSource) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT state FROM ssp_billing_delivery")) {
            result.next();
            return result.getString(1);
        }
    }

    private record ClaimAttempt(String digest, RenderAcceptance result) {
    }

    private static HikariDataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getProperty(
                "ssp.claim.jdbc-url",
                "jdbc:postgresql://localhost:15432/rtb"
        ));
        config.setUsername(System.getProperty("ssp.claim.username", "postgres"));
        config.setPassword(System.getProperty("ssp.claim.password", "local-dev-postgres-password"));
        config.setMaximumPoolSize(8);
        return new HikariDataSource(config);
    }
}
