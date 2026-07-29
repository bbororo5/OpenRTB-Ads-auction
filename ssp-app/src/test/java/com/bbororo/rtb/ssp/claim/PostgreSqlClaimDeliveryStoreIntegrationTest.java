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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("ssp-claim-store")
class PostgreSqlClaimDeliveryStoreIntegrationTest {

    @Test
    void persistsDeduplicatesLeasesAndCompletes() throws Exception {
        try (HikariDataSource dataSource = dataSource()) {
            try (var connection = dataSource.getConnection();
                 var statement = connection.createStatement()) {
                statement.executeUpdate("TRUNCATE TABLE ssp_billing_delivery");
            }
            var store = new PostgreSqlClaimDeliveryStore(dataSource, Duration.ofSeconds(1));
            Instant now = Instant.now();
            BillingClaim claim = new BillingClaim(
                    "provider-1", "request-1", "imp-1", "auction-1/imp-1",
                    "a".repeat(64), "project-dsp", 2_000,
                    URI.create("https://project-dsp.test/burl/1"), now.plusSeconds(5)
            );

            assertEquals(RenderAcceptance.ACCEPTED, store.recordClaimAndScheduleDelivery(claim));
            assertEquals(RenderAcceptance.DUPLICATE, store.recordClaimAndScheduleDelivery(claim));
            var leased = store.leaseDueDelivery(now).orElseThrow();
            assertEquals(2_000L, leased.task().claim().cpmKrw());

            store.completeOrReleaseDelivery(leased.lease(), DeliveryOutcome.DELIVERED, now.plusMillis(10));
            assertTrue(store.leaseDueDelivery(now.plusMillis(20)).isEmpty());
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
        config.setMaximumPoolSize(2);
        return new HikariDataSource(config);
    }
}
