package com.bbororo.rtb.dsp.lease;

import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult.ALREADY_APPLIED;
import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult.APPLIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseSupplySnapshot;
import com.bbororo.rtb.dsp.lease.LeaseMessages.ClaimDueSettlements;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefilled;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlement;
import com.bbororo.rtb.dsp.lease.LeaseMessages.RefillLease;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("dsp-regional-ledger")
class PostgreSqlRegionalBudgetLedgerIntegrationTest {

    private static HikariDataSource dataSource;
    private static java.util.concurrent.ExecutorService jdbcExecutor;
    private PostgreSqlRegionalBudgetLedger ledger;

    @BeforeAll
    static void connect() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getProperty("dsp.ledger.jdbc-url"));
        config.setUsername(System.getProperty("dsp.ledger.username"));
        config.setPassword(System.getProperty("dsp.ledger.password"));
        config.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(config);
        jdbcExecutor = Executors.newFixedThreadPool(8);
    }

    @AfterAll
    static void close() {
        jdbcExecutor.close();
        dataSource.close();
    }

    @BeforeEach
    void reset() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement reset = connection.prepareStatement(
                     "TRUNCATE budget_lease, regional_campaign_budget CASCADE"
             );
             PreparedStatement seed = connection.prepareStatement("""
                     INSERT INTO regional_campaign_budget (
                         campaign_id, responsibility_micros, available_micros,
                         campaign_starts_at, campaign_ends_at
                     ) VALUES ('campaign-1', 1000000, 1000000,
                               transaction_timestamp() - interval '1 minute',
                               transaction_timestamp() + interval '1 hour')
                     """)) {
            reset.executeUpdate();
            seed.executeUpdate();
        }
        ledger = new PostgreSqlRegionalBudgetLedger(
                dataSource,
                jdbcExecutor,
                new PostgreSqlRegionalBudgetLedger.Settings(
                        Duration.ofSeconds(5), Duration.ofMinutes(5),
                        Duration.ofSeconds(5), Duration.ofMillis(100),
                        1_000, 100_000
                )
        );
    }

    @Test
    void retryReturnsOneLeaseAndSubtractsTheCampaignOnce() throws Exception {
        RefillLease command = refill("request-1", 50_000);

        LeaseRefilled first = assertInstanceOf(
                LeaseRefilled.class,
                ledger.issue(command).toCompletableFuture().join()
        );
        LeaseRefilled retry = assertInstanceOf(
                LeaseRefilled.class,
                ledger.issue(command).toCompletableFuture().join()
        );

        assertEquals(first, retry);
        assertEquals(1, count("SELECT count(*) FROM budget_lease"));
        assertEquals(first.lease().faceValueMicros(), scalar(
                "SELECT outstanding_micros FROM regional_campaign_budget WHERE campaign_id='campaign-1'"
        ));
    }

    @Test
    void claimedSettlementReturnsUnusedMoneyExactlyOnce() throws Exception {
        LeaseRefilled issued = assertInstanceOf(
                LeaseRefilled.class,
                ledger.issue(refill("request-1", 50_000)).toCompletableFuture().join()
        );
        execute("""
                UPDATE budget_lease
                   SET issued_at = transaction_timestamp() - interval '3 seconds',
                       expires_at = transaction_timestamp() - interval '2 seconds',
                       safe_recovery_at = transaction_timestamp() - interval '1 second'
                """);
        var work = ledger.claimDue(new ClaimDueSettlements(
                "worker-1", 10, Duration.ofSeconds(2)
        )).toCompletableFuture().join().getFirst();
        var settlement = new LeaseSettlement(
                work.leaseId(), work.settlementGeneration(), work.faceValueMicros(),
                10_000, work.faceValueMicros() - 10_000, 0
        );

        assertEquals(APPLIED, ledger.apply(work, settlement).toCompletableFuture().join());
        assertEquals(ALREADY_APPLIED, ledger.apply(work, settlement).toCompletableFuture().join());
        assertEquals(10_000, scalar(
                "SELECT committed_micros FROM regional_campaign_budget WHERE campaign_id='campaign-1'"
        ));
        assertEquals(0, scalar(
                "SELECT outstanding_micros FROM regional_campaign_budget WHERE campaign_id='campaign-1'"
        ));
        assertEquals(
                1_000_000 - 10_000,
                scalar("SELECT available_micros FROM regional_campaign_budget WHERE campaign_id='campaign-1'")
        );
        assertEquals(issued.lease().leaseId(), work.leaseId());
    }

    private static RefillLease refill(String requestId, long requestedMicros) {
        Instant observed = Instant.parse("2026-01-01T00:00:00Z");
        var snapshot = new LeaseSupplySnapshot(
                "campaign-1", 0, 0, 0, 0, 0, 0, Optional.empty(), observed
        );
        return new RefillLease(requestId, "dsp-1", snapshot, requestedMicros);
    }

    private static long count(String sql) throws Exception {
        return scalar(sql);
    }

    private static long scalar(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet row = statement.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }
}
