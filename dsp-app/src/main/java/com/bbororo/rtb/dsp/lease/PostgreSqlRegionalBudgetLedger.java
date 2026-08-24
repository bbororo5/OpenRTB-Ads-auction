package com.bbororo.rtb.dsp.lease;

import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefillRejection.PACING_LIMIT_REACHED;
import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefillRejection.REGIONAL_BUDGET_UNAVAILABLE;
import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefillRejection.REGIONAL_LEDGER_UNAVAILABLE;
import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult.ALREADY_APPLIED;
import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult.APPLIED;
import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult.CONFLICT;
import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult.STALE_CLAIM;
import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult.TEMPORARILY_UNAVAILABLE;

import com.bbororo.rtb.dsp.spending.api.SpendingMessages.InstallLease;
import com.bbororo.rtb.dsp.lease.LeaseMessages.ClaimDueSettlements;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefillRejected;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefillResult;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefilled;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementAmounts;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlementResult;
import com.bbororo.rtb.dsp.lease.LeaseMessages.RefillLease;
import com.bbororo.rtb.dsp.lease.LeaseMessages.SettlementWork;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import javax.sql.DataSource;

/** PostgreSQL 트랜잭션으로 리전 책임액과 리스 발급·정산 불변식을 보존한다. */
public final class PostgreSqlRegionalBudgetLedger implements RegionalBudgetLedger {

    private static final String FIND_REQUEST = """
            SELECT lease_id, campaign_id, owner_instance_id, requested_micros,
                   face_value_micros, lease_generation, issued_at, expires_at
              FROM budget_lease
             WHERE request_id = ?
            """;
    private static final String LOCK_CAMPAIGN = """
            SELECT available_micros, outstanding_micros, campaign_ends_at,
                   next_lease_generation, transaction_timestamp() AS ledger_now
              FROM regional_campaign_budget
             WHERE campaign_id = ?
             FOR UPDATE
            """;
    private static final String INSERT_LEASE = """
            INSERT INTO budget_lease (
                lease_id, request_id, requested_micros, campaign_id, owner_instance_id, face_value_micros,
                lease_generation, issued_at, expires_at, safe_recovery_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String ALLOCATE_CAMPAIGN = """
            UPDATE regional_campaign_budget
               SET available_micros = available_micros - ?,
                   outstanding_micros = outstanding_micros + ?,
                   next_lease_generation = next_lease_generation + 1
             WHERE campaign_id = ?
            """;
    private static final String CLAIM_DUE = """
            WITH due AS (
                SELECT lease_id
                  FROM budget_lease
                 WHERE safe_recovery_at <= transaction_timestamp()
                   AND settlement_state <> 'SETTLED'
                   AND (
                       settlement_state = 'PENDING'
                       OR claim_until IS NULL
                       OR claim_until <= transaction_timestamp()
                   )
                 ORDER BY safe_recovery_at, lease_id
                 FOR UPDATE SKIP LOCKED
                 LIMIT ?
            )
            UPDATE budget_lease lease
               SET settlement_state = 'CLAIMED',
                   claim_generation = lease.claim_generation + 1,
                   claimed_by = ?,
                   claim_until = transaction_timestamp() + (? * interval '1 millisecond')
              FROM due
             WHERE lease.lease_id = due.lease_id
            RETURNING lease.lease_id, lease.campaign_id, lease.owner_instance_id,
                      lease.face_value_micros, lease.settlement_generation,
                      lease.claim_generation, lease.safe_recovery_at, lease.claim_until
            """;
    private static final String LOCK_SETTLEMENT = """
            SELECT campaign_id, face_value_micros, settlement_generation, settlement_state,
                   claim_generation, claim_until, committed_micros, returned_micros,
                   quarantined_micros, transaction_timestamp() AS ledger_now
              FROM budget_lease
             WHERE lease_id = ?
             FOR UPDATE
            """;
    private static final String SETTLE_LEASE = """
            UPDATE budget_lease
               SET settlement_state = 'SETTLED', committed_micros = ?, returned_micros = ?,
                   quarantined_micros = ?, settled_at = transaction_timestamp()
             WHERE lease_id = ?
            """;
    private static final String SETTLE_CAMPAIGN = """
            UPDATE regional_campaign_budget
               SET available_micros = available_micros + ?,
                   outstanding_micros = outstanding_micros - ?,
                   committed_micros = committed_micros + ?,
                   quarantined_micros = quarantined_micros + ?
             WHERE campaign_id = ? AND outstanding_micros >= ?
            """;

    private final DataSource dataSource;
    private final Executor jdbcExecutor;
    private final Settings settings;

    public PostgreSqlRegionalBudgetLedger(
            DataSource dataSource,
            Executor jdbcExecutor,
            Settings settings
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.jdbcExecutor = Objects.requireNonNull(jdbcExecutor, "jdbcExecutor");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public CompletionStage<LeaseRefillResult> issue(RefillLease command) {
        Objects.requireNonNull(command, "command");
        return CompletableFuture.supplyAsync(() -> issueBlocking(command), jdbcExecutor);
    }

    @Override
    public CompletionStage<List<SettlementWork>> claimDue(ClaimDueSettlements command) {
        Objects.requireNonNull(command, "command");
        return CompletableFuture.supplyAsync(() -> claimDueBlocking(command), jdbcExecutor);
    }

    @Override
    public CompletionStage<LeaseSettlementResult> apply(
            SettlementWork work,
            LeaseSettlementAmounts settlement
    ) {
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(settlement, "settlement");
        return CompletableFuture.supplyAsync(() -> applyBlocking(work, settlement), jdbcExecutor);
    }

    private LeaseRefillResult issueBlocking(RefillLease command) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                configureTransaction(connection);
                ExistingLease existing = findRequest(connection, command.requestId());
                if (existing != null) {
                    connection.commit();
                    return existing.resultFor(command);
                }
                CampaignRow campaign = lockCampaign(connection, command.campaignId());
                if (campaign == null || !campaign.ledgerNow().isBefore(campaign.endsAt())) {
                    connection.rollback();
                    return new LeaseRefillRejected(REGIONAL_BUDGET_UNAVAILABLE);
                }
                existing = findRequest(connection, command.requestId());
                if (existing != null) {
                    connection.commit();
                    return existing.resultFor(command);
                }

                long grant = grantAmount(command.requestedMicros(), campaign);
                if (grant == 0L) {
                    connection.rollback();
                    return new LeaseRefillRejected(
                            campaign.availableMicros() == 0L
                                    ? REGIONAL_BUDGET_UNAVAILABLE
                                    : PACING_LIMIT_REACHED
                    );
                }
                InstallLease lease = insertLease(connection, command, campaign, grant);
                allocateCampaign(connection, command.campaignId(), grant);
                connection.commit();
                return new LeaseRefilled(lease);
            } catch (SQLException | RuntimeException failure) {
                rollbackQuietly(connection);
                return recoverIssue(command, failure);
            }
        } catch (SQLException failure) {
            return new LeaseRefillRejected(REGIONAL_LEDGER_UNAVAILABLE);
        }
    }

    private List<SettlementWork> claimDueBlocking(ClaimDueSettlements command) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(CLAIM_DUE)) {
            statement.setInt(1, command.limit());
            statement.setString(2, command.workerId());
            statement.setLong(3, Math.max(1L, command.claimDuration().toMillis()));
            try (ResultSet rows = statement.executeQuery()) {
                List<SettlementWork> work = new ArrayList<>();
                while (rows.next()) {
                    work.add(new SettlementWork(
                            rows.getObject("lease_id", UUID.class).toString(),
                            rows.getString("campaign_id"),
                            rows.getString("owner_instance_id"),
                            rows.getLong("face_value_micros"),
                            rows.getLong("settlement_generation"),
                            rows.getLong("claim_generation"),
                            instant(rows, "safe_recovery_at"),
                            instant(rows, "claim_until")
                    ));
                }
                return List.copyOf(work);
            }
        } catch (SQLException failure) {
            throw new LedgerAccessException("failed to claim due settlements", failure);
        }
    }

    private LeaseSettlementResult applyBlocking(
            SettlementWork work,
            LeaseSettlementAmounts settlement
    ) {
        if (!work.leaseId().equals(settlement.leaseId())
                || work.faceValueMicros() != settlement.faceValueMicros()
                || work.settlementGeneration() != settlement.settlementGeneration()) {
            return CONFLICT;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                configureTransaction(connection);
                SettlementRow row = lockSettlement(connection, work.leaseId());
                LeaseSettlementResult precondition = settlementPrecondition(work, settlement, row);
                if (precondition != null) {
                    connection.rollback();
                    return precondition;
                }
                settleLease(connection, settlement);
                if (!settleCampaign(connection, row.campaignId(), settlement)) {
                    connection.rollback();
                    return CONFLICT;
                }
                connection.commit();
                return APPLIED;
            } catch (SQLException | RuntimeException failure) {
                rollbackQuietly(connection);
                return TEMPORARILY_UNAVAILABLE;
            }
        } catch (SQLException failure) {
            return TEMPORARILY_UNAVAILABLE;
        }
    }

    private long grantAmount(long requested, CampaignRow campaign) {
        long uncommitted = Math.addExact(campaign.availableMicros(), campaign.outstandingMicros());
        long remainingMillis = Math.max(
                1L,
                Duration.between(campaign.ledgerNow(), campaign.endsAt()).toMillis()
        );
        double paced = (double) uncommitted * settings.pacingCoverage().toMillis() / remainingMillis;
        long targetOutstanding = Math.max(
                settings.minimumGrantMicros(),
                paced >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.ceil(paced)
        );
        long headroom = Math.max(0L, targetOutstanding - campaign.outstandingMicros());
        return min(requested, settings.maximumLeaseMicros(), campaign.availableMicros(), headroom);
    }

    private InstallLease insertLease(
            Connection connection,
            RefillLease command,
            CampaignRow campaign,
            long grant
    ) throws SQLException {
        UUID leaseId = UUID.randomUUID();
        Instant expiresAt = campaign.ledgerNow().plus(settings.leaseDuration());
        Instant safeRecoveryAt = expiresAt
                .plus(settings.maximumReservationLifetime())
                .plus(settings.eventVisibilityMargin());
        try (PreparedStatement statement = connection.prepareStatement(INSERT_LEASE)) {
            statement.setObject(1, leaseId);
            statement.setString(2, command.requestId());
            statement.setLong(3, command.requestedMicros());
            statement.setString(4, command.campaignId());
            statement.setString(5, command.instanceId());
            statement.setLong(6, grant);
            statement.setLong(7, campaign.nextGeneration());
            statement.setTimestamp(8, Timestamp.from(campaign.ledgerNow()));
            statement.setTimestamp(9, Timestamp.from(expiresAt));
            statement.setTimestamp(10, Timestamp.from(safeRecoveryAt));
            statement.executeUpdate();
        }
        return new InstallLease(
                leaseId.toString(), command.campaignId(), grant, campaign.nextGeneration(),
                campaign.ledgerNow(), expiresAt
        );
    }

    private void allocateCampaign(Connection connection, String campaignId, long grant)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ALLOCATE_CAMPAIGN)) {
            statement.setLong(1, grant);
            statement.setLong(2, grant);
            statement.setString(3, campaignId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("campaign allocation was not applied");
            }
        }
    }

    private ExistingLease findRequest(Connection connection, String requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_REQUEST)) {
            statement.setString(1, requestId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                return new ExistingLease(
                        row.getString("owner_instance_id"),
                        row.getLong("requested_micros"),
                        new InstallLease(
                                row.getObject("lease_id", UUID.class).toString(),
                                row.getString("campaign_id"),
                                row.getLong("face_value_micros"),
                                row.getLong("lease_generation"),
                                instant(row, "issued_at"),
                                instant(row, "expires_at")
                        )
                );
            }
        }
    }

    private CampaignRow lockCampaign(Connection connection, String campaignId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_CAMPAIGN)) {
            statement.setString(1, campaignId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                return new CampaignRow(
                        row.getLong("available_micros"),
                        row.getLong("outstanding_micros"),
                        instant(row, "campaign_ends_at"),
                        row.getLong("next_lease_generation"),
                        instant(row, "ledger_now")
                );
            }
        }
    }

    private SettlementRow lockSettlement(Connection connection, String leaseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_SETTLEMENT)) {
            statement.setObject(1, UUID.fromString(leaseId));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                return new SettlementRow(
                        row.getString("campaign_id"), row.getLong("face_value_micros"),
                        row.getLong("settlement_generation"), row.getString("settlement_state"),
                        row.getLong("claim_generation"), instantOrNull(row, "claim_until"),
                        nullableLong(row, "committed_micros"), nullableLong(row, "returned_micros"),
                        nullableLong(row, "quarantined_micros"), instant(row, "ledger_now")
                );
            }
        }
    }

    private LeaseSettlementResult settlementPrecondition(
            SettlementWork work,
            LeaseSettlementAmounts settlement,
            SettlementRow row
    ) {
        if (row == null
                || row.faceValueMicros() != settlement.faceValueMicros()
                || row.settlementGeneration() != settlement.settlementGeneration()) {
            return CONFLICT;
        }
        if ("SETTLED".equals(row.state())) {
            return row.matches(settlement) ? ALREADY_APPLIED : CONFLICT;
        }
        if (!"CLAIMED".equals(row.state())
                || row.claimGeneration() != work.claimGeneration()
                || row.claimUntil() == null
                || !row.ledgerNow().isBefore(row.claimUntil())) {
            return STALE_CLAIM;
        }
        return null;
    }

    private void settleLease(
            Connection connection,
            LeaseSettlementAmounts settlement
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SETTLE_LEASE)) {
            statement.setLong(1, settlement.committedMicros());
            statement.setLong(2, settlement.returnedMicros());
            statement.setLong(3, settlement.quarantinedMicros());
            statement.setObject(4, UUID.fromString(settlement.leaseId()));
            statement.executeUpdate();
        }
    }

    private boolean settleCampaign(
            Connection connection,
            String campaignId,
            LeaseSettlementAmounts settlement
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SETTLE_CAMPAIGN)) {
            statement.setLong(1, settlement.returnedMicros());
            statement.setLong(2, settlement.faceValueMicros());
            statement.setLong(3, settlement.committedMicros());
            statement.setLong(4, settlement.quarantinedMicros());
            statement.setString(5, campaignId);
            statement.setLong(6, settlement.faceValueMicros());
            return statement.executeUpdate() == 1;
        }
    }

    private LeaseRefillResult recoverIssue(RefillLease command, Exception failure) {
        if (failure instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
            try (Connection connection = dataSource.getConnection()) {
                ExistingLease existing = findRequest(connection, command.requestId());
                if (existing != null) {
                    return existing.resultFor(command);
                }
            } catch (SQLException ignored) {
                // 아래의 일시 장애 결과로 수렴한다.
            }
        }
        return new LeaseRefillRejected(REGIONAL_LEDGER_UNAVAILABLE);
    }

    private void configureTransaction(Connection connection) throws SQLException {
        try (PreparedStatement lockTimeout = connection.prepareStatement(
                "SET LOCAL lock_timeout = '100ms'"
        ); PreparedStatement statementTimeout = connection.prepareStatement(
                "SET LOCAL statement_timeout = '500ms'"
        )) {
            lockTimeout.execute();
            statementTimeout.execute();
        }
    }

    private static long min(long first, long... rest) {
        long result = first;
        for (long value : rest) {
            result = Math.min(result, value);
        }
        return Math.max(0L, result);
    }

    private static Instant instant(ResultSet row, String name) throws SQLException {
        return row.getTimestamp(name).toInstant();
    }

    private static Instant instantOrNull(ResultSet row, String name) throws SQLException {
        Timestamp value = row.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet row, String name) throws SQLException {
        long value = row.getLong(name);
        return row.wasNull() ? null : value;
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 원래 실패를 보존한다.
        }
    }

    public record Settings(
            Duration leaseDuration,
            Duration pacingCoverage,
            Duration maximumReservationLifetime,
            Duration eventVisibilityMargin,
            long minimumGrantMicros,
            long maximumLeaseMicros
    ) {
        public Settings {
            requirePositive(leaseDuration, "leaseDuration");
            requirePositive(pacingCoverage, "pacingCoverage");
            requirePositive(maximumReservationLifetime, "maximumReservationLifetime");
            requirePositive(eventVisibilityMargin, "eventVisibilityMargin");
            if (minimumGrantMicros <= 0 || maximumLeaseMicros < minimumGrantMicros) {
                throw new IllegalArgumentException("grant bounds are invalid");
            }
        }

        private static void requirePositive(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }

    private record CampaignRow(
            long availableMicros,
            long outstandingMicros,
            Instant endsAt,
            long nextGeneration,
            Instant ledgerNow
    ) {
    }

    private record ExistingLease(String ownerInstanceId, long requestedMicros, InstallLease lease) {
        LeaseRefillResult resultFor(RefillLease command) {
            if (!ownerInstanceId.equals(command.instanceId())
                    || requestedMicros != command.requestedMicros()
                    || !lease.campaignId().equals(command.campaignId())) {
                return new LeaseRefillRejected(LeaseMessages.LeaseRefillRejection.STALE_REQUEST);
            }
            return new LeaseRefilled(lease);
        }
    }

    private record SettlementRow(
            String campaignId,
            long faceValueMicros,
            long settlementGeneration,
            String state,
            long claimGeneration,
            Instant claimUntil,
            Long committedMicros,
            Long returnedMicros,
            Long quarantinedMicros,
            Instant ledgerNow
    ) {
        boolean matches(LeaseSettlementAmounts settlement) {
            return Objects.equals(committedMicros, settlement.committedMicros())
                    && Objects.equals(returnedMicros, settlement.returnedMicros())
                    && Objects.equals(quarantinedMicros, settlement.quarantinedMicros());
        }
    }

    private static final class LedgerAccessException extends RuntimeException {
        private LedgerAccessException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
