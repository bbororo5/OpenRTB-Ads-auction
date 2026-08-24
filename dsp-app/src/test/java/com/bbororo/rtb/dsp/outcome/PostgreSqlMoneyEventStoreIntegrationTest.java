package com.bbororo.rtb.dsp.outcome;

import static com.bbororo.rtb.dsp.outcome.NoticeProcessingMessages.MonetaryEventKind.BILLING;
import static com.bbororo.rtb.dsp.outcome.NoticeProcessingMessages.MonetaryEventKind.EXPIRY;
import static com.bbororo.rtb.dsp.outcome.NoticeProcessingMessages.MonetaryEventKind.LOSS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.dsp.outcome.NoticeProcessingMessages.MonetaryEventKind;
import com.bbororo.rtb.dsp.outcome.NoticeProcessingMessages.MonetaryNoticeEvent;
import com.bbororo.rtb.dsp.outcome.NoticeProcessingMessages.OutcomeChosen;
import com.bbororo.rtb.dsp.outcome.NoticeProcessingMessages.OutcomeConflict;
import com.bbororo.rtb.dsp.outcome.NoticeProcessingMessages.OutcomeDecision;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("dsp-money-event-store")
class PostgreSqlMoneyEventStoreIntegrationTest {

    private static HikariDataSource dataSource;
    private static ExecutorService jdbcExecutor;
    private PostgreSqlMoneyEventJournal journal;
    private PostgreSqlLeaseOutcomeView reader;

    @BeforeAll
    static void connect() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getProperty("dsp.money.jdbc-url"));
        config.setUsername(System.getProperty("dsp.money.username"));
        config.setPassword(System.getProperty("dsp.money.password"));
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
             PreparedStatement statement = connection.prepareStatement(
                     "TRUNCATE monetary_event_conflict, reservation_monetary_outcome, monetary_event CASCADE"
             )) {
            statement.executeUpdate();
        }
        journal = new PostgreSqlMoneyEventJournal(dataSource, jdbcExecutor);
        reader = new PostgreSqlLeaseOutcomeView(dataSource, jdbcExecutor);
    }

    @Test
    void appendsOnceAndQuarantinesAContradictoryTerminalEvent() {
        String leaseId = UUID.randomUUID().toString();
        Instant deadline = Instant.now().plusSeconds(1);
        var billing = event("reservation-1", leaseId, BILLING, 300, deadline, Instant.now());
        var loss = event("reservation-1", leaseId, LOSS, 300, deadline, Instant.now());

        OutcomeChosen first = assertInstanceOf(OutcomeChosen.class, decide(billing));
        OutcomeChosen duplicate = assertInstanceOf(OutcomeChosen.class, decide(billing));
        OutcomeConflict conflict = assertInstanceOf(OutcomeConflict.class, decide(loss));
        assertTrue(first.firstDecision());
        assertTrue(!duplicate.firstDecision());
        assertEquals(BILLING, conflict.outcome().kind());

        var summary = reader.summarize(
                leaseId, 1_000, Instant.now().minusSeconds(1)
        ).toCompletableFuture().join();
        assertEquals(0, summary.committedMicros());
        assertEquals(700, summary.returnableMicros());
        assertEquals(300, summary.quarantinedMicros());
        assertTrue(summary.safeRecoveryReached());
    }

    @Test
    void commitsOnlyOnTimeBillingAndReturnsTheRestOfTheLease() {
        String leaseId = UUID.randomUUID().toString();
        Instant deadline = Instant.now().plusSeconds(1);
        decide(event("reservation-1", leaseId, BILLING, 300, deadline, deadline.minusMillis(1)));
        decide(event("reservation-2", leaseId, EXPIRY, 200, deadline, deadline));
        decide(event("reservation-3", leaseId, BILLING, 100, deadline, deadline.plusMillis(1)));

        var summary = reader.summarize(
                leaseId, 1_000, Instant.now().minusSeconds(1)
        ).toCompletableFuture().join();

        assertEquals(300, summary.committedMicros());
        assertEquals(700, summary.returnableMicros());
        assertEquals(0, summary.quarantinedMicros());
    }

    private OutcomeDecision decide(MonetaryNoticeEvent event) {
        return journal.decide(event).toCompletableFuture().join();
    }

    private static MonetaryNoticeEvent event(
            String reservationId,
            String leaseId,
            MonetaryEventKind kind,
            long amount,
            Instant deadline,
            Instant receivedAt
    ) {
        return new MonetaryNoticeEvent(
                reservationId + ':' + kind,
                kind,
                reservationId,
                leaseId,
                "campaign-1",
                amount,
                deadline,
                receivedAt
        );
    }
}
