package com.bbororo.rtb.dsp.lease;

import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult.APPLIED;
import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult.STALE_CLAIM;
import static com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.MonetaryEventKind.BILLING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.bbororo.rtb.dsp.spending.SpendingMessages.LeaseSupplySnapshot;
import com.bbororo.rtb.dsp.lease.LeaseMessages.ClaimDueSettlements;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefilled;
import com.bbororo.rtb.dsp.lease.LeaseMessages.RefillLease;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.MonetaryNoticeEvent;
import com.bbororo.rtb.dsp.outcome.PostgreSqlReservationOutcomeStore;
import com.bbororo.rtb.dsp.outcome.PostgreSqlLeaseOutcomeView;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("dsp-lease-recovery")
class LeaseFailureRecoveryIntegrationTest {

    private static HikariDataSource ledgerDataSource;
    private static HikariDataSource moneyDataSource;
    private static ExecutorService jdbcExecutor;
    private PostgreSqlRegionalBudgetLedger ledger;
    private PostgreSqlReservationOutcomeStore journal;
    private LeaseSettlementService settlementService;

    @BeforeAll
    static void connect() {
        String username = System.getProperty("dsp.store.username");
        String password = System.getProperty("dsp.store.password");
        ledgerDataSource = dataSource(System.getProperty("dsp.ledger.jdbc-url"), username, password);
        moneyDataSource = dataSource(System.getProperty("dsp.money.jdbc-url"), username, password);
        jdbcExecutor = Executors.newFixedThreadPool(8);
    }

    @AfterAll
    static void close() {
        jdbcExecutor.close();
        ledgerDataSource.close();
        moneyDataSource.close();
    }

    @BeforeEach
    void reset() throws Exception {
        execute(ledgerDataSource, "TRUNCATE budget_lease, regional_campaign_budget CASCADE");
        execute(moneyDataSource,
                "TRUNCATE monetary_event_conflict, reservation_monetary_outcome, monetary_event CASCADE");
        execute(ledgerDataSource, """
                INSERT INTO regional_campaign_budget (
                    campaign_id, responsibility_micros, available_micros,
                    campaign_starts_at, campaign_ends_at
                ) VALUES ('campaign-1', 1000000, 1000000,
                          transaction_timestamp() - interval '1 minute',
                          transaction_timestamp() + interval '1 hour')
                """);
        ledger = new PostgreSqlRegionalBudgetLedger(
                ledgerDataSource,
                jdbcExecutor,
                new PostgreSqlRegionalBudgetLedger.Settings(
                        Duration.ofSeconds(5), Duration.ofMinutes(5),
                        Duration.ofSeconds(2), Duration.ofMillis(100), 1_000, 100_000
                )
        );
        journal = new PostgreSqlReservationOutcomeStore(moneyDataSource, jdbcExecutor);
        settlementService = new LeaseSettlementService(
                ledger, new PostgreSqlLeaseOutcomeView(moneyDataSource, jdbcExecutor)
        );
    }

    @Test
    void anotherWorkerRecoversADeadDspLeaseWithoutReusingUncertainMoney() throws Exception {
        LeaseRefilled issued = assertInstanceOf(
                LeaseRefilled.class,
                ledger.issue(refill()).toCompletableFuture().join()
        );
        String leaseId = issued.lease().leaseId();
        Instant deadline = Instant.now().plusSeconds(1);
        journal.decide(new MonetaryNoticeEvent(
                "reservation-1:BILLING", BILLING, "reservation-1", leaseId,
                "campaign-1", 300, deadline, deadline.minusMillis(1)
        )).toCompletableFuture().join();
        forceLeaseRecoverable();

        var staleWork = ledger.claimDue(new ClaimDueSettlements(
                "worker-that-dies", 1, Duration.ofSeconds(1)
        )).toCompletableFuture().join().getFirst();
        execute(ledgerDataSource,
                "UPDATE budget_lease SET claim_until = transaction_timestamp() - interval '1 second'");
        var recoveryWork = ledger.claimDue(new ClaimDueSettlements(
                "recovery-worker", 1, Duration.ofSeconds(1)
        )).toCompletableFuture().join().getFirst();

        assertEquals(STALE_CLAIM, settlementService.settle(staleWork).toCompletableFuture().join());
        assertEquals(APPLIED, settlementService.settle(recoveryWork).toCompletableFuture().join());
        assertEquals(300, scalar(ledgerDataSource,
                "SELECT committed_micros FROM regional_campaign_budget WHERE campaign_id='campaign-1'"));
        assertEquals(999_700, scalar(ledgerDataSource,
                "SELECT available_micros FROM regional_campaign_budget WHERE campaign_id='campaign-1'"));
        assertEquals(0, scalar(ledgerDataSource,
                "SELECT outstanding_micros FROM regional_campaign_budget WHERE campaign_id='campaign-1'"));
    }

    @Test
    void leaseLostBeforeLocalInstallationReturnsOnlyAfterSafeRecovery() throws Exception {
        assertInstanceOf(
                LeaseRefilled.class,
                ledger.issue(refill()).toCompletableFuture().join()
        );
        assertEquals(999_000, scalar(ledgerDataSource,
                "SELECT available_micros FROM regional_campaign_budget WHERE campaign_id='campaign-1'"));
        forceLeaseRecoverable();
        var work = ledger.claimDue(new ClaimDueSettlements(
                "recovery-worker", 1, Duration.ofSeconds(1)
        )).toCompletableFuture().join().getFirst();

        assertEquals(APPLIED, settlementService.settle(work).toCompletableFuture().join());
        assertEquals(1_000_000, scalar(ledgerDataSource,
                "SELECT available_micros FROM regional_campaign_budget WHERE campaign_id='campaign-1'"));
    }

    private static RefillLease refill() {
        Instant observed = Instant.parse("2026-01-01T00:00:00Z");
        return new RefillLease(
                "request-1", "dsp-that-dies",
                new LeaseSupplySnapshot(
                        "campaign-1", 0, 0, 0, 0, 0, 0, Optional.empty(), observed
                ),
                1_000
        );
    }

    private void forceLeaseRecoverable() throws Exception {
        execute(ledgerDataSource, """
                UPDATE budget_lease
                   SET issued_at = transaction_timestamp() - interval '4 seconds',
                       expires_at = transaction_timestamp() - interval '3 seconds',
                       safe_recovery_at = transaction_timestamp() - interval '1 second'
                """);
    }

    private static HikariDataSource dataSource(String url, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(8);
        return new HikariDataSource(config);
    }

    private static void execute(HikariDataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static long scalar(HikariDataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet row = statement.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }
}
