package com.bbororo.rtb.dsp.outcome.internal;

import com.bbororo.rtb.dsp.outcome.api.LeaseOutcomeView.LeaseOutcomeSummary;
import com.bbororo.rtb.dsp.outcome.api.LeaseOutcomeView;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import javax.sql.DataSource;

/** 지역 금액 사건의 최초 결과와 충돌을 리스 액면 분류로 재생한다. */
public final class PostgreSqlLeaseOutcomeView implements LeaseOutcomeView {

    private static final String SUMMARIZE = """
            SELECT
                COALESCE(SUM(CASE
                    WHEN conflict.reservation_id IS NOT NULL THEN 0
                    WHEN outcome.kind = 'BILLING'
                     AND outcome.occurred_at <= outcome.reservation_expires_at
                    THEN outcome.impression_amount_micros
                    ELSE 0 END), 0) AS committed_micros,
                COALESCE(SUM(CASE
                    WHEN conflict.reservation_id IS NOT NULL
                    THEN outcome.impression_amount_micros
                    ELSE 0 END), 0) AS quarantined_micros,
                transaction_timestamp() >= ? AS recovery_time_reached
            FROM reservation_monetary_outcome outcome
            LEFT JOIN (
                SELECT DISTINCT reservation_id
                  FROM monetary_event_conflict
                 WHERE lease_id = ?
            ) conflict ON conflict.reservation_id = outcome.reservation_id
            WHERE outcome.lease_id = ?
            """;

    private final DataSource dataSource;
    private final Executor jdbcExecutor;

    public PostgreSqlLeaseOutcomeView(DataSource dataSource, Executor jdbcExecutor) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.jdbcExecutor = Objects.requireNonNull(jdbcExecutor, "jdbcExecutor");
    }

    @Override
    public CompletionStage<LeaseOutcomeSummary> summarize(
            String leaseId,
            long faceValueMicros,
            Instant evaluatedAt
    ) {
        Objects.requireNonNull(leaseId, "leaseId");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if (faceValueMicros <= 0L) {
            throw new IllegalArgumentException("faceValueMicros must be positive");
        }
        return CompletableFuture.supplyAsync(
                () -> summarizeBlocking(leaseId, faceValueMicros, evaluatedAt),
                jdbcExecutor
        );
    }

    private LeaseOutcomeSummary summarizeBlocking(
            String leaseId,
            long faceValueMicros,
            Instant evaluatedAt
    ) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SUMMARIZE)) {
            UUID id = UUID.fromString(leaseId);
            statement.setTimestamp(1, Timestamp.from(evaluatedAt));
            statement.setObject(2, id);
            statement.setObject(3, id);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                long committed = row.getLong("committed_micros");
                long quarantined = row.getLong("quarantined_micros");
                boolean ready = row.getBoolean("recovery_time_reached");
                if (committed < 0L || quarantined < 0L
                        || committed > faceValueMicros - Math.min(faceValueMicros, quarantined)) {
                    committed = 0L;
                    quarantined = faceValueMicros;
                }
                long returned = faceValueMicros - committed - quarantined;
                return new LeaseOutcomeSummary(
                        leaseId, faceValueMicros, committed, returned, quarantined, ready
                );
            }
        } catch (SQLException | IllegalArgumentException failure) {
            throw new LeaseOutcomeReadException("failed to summarize lease outcomes", failure);
        }
    }

    private static final class LeaseOutcomeReadException extends RuntimeException {
        private LeaseOutcomeReadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
