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
            var leased = store.leaseDueDelivery(now).orElseThrow();
            assertEquals(2_000L, leased.task().claim().cpmMilliKrw());

            store.completeOrReleaseDelivery(leased.lease(), DeliveryOutcome.DELIVERED, now.plusMillis(10));
            assertTrue(store.leaseDueDelivery(now.plusMillis(20)).isEmpty());
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
