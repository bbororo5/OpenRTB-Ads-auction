package com.bbororo.rtb.dsp.outcome;

import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.MonetaryEventKind;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.MonetaryNoticeEvent;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.OutcomeChosen;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.OutcomeConflict;
import com.bbororo.rtb.dsp.outcome.ReservationOutcomeMessages.OutcomeDecision;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import javax.sql.DataSource;

/** 지역 PostgreSQL에 종결 시도와 최초 금액 결과를 한 트랜잭션으로 기록한다. */
public final class PostgreSqlReservationOutcomeStore implements ReservationOutcomeStore {

    private static final String INSERT_EVENT = """
            INSERT INTO monetary_event (
                event_id, kind, reservation_id, lease_id, campaign_id,
                impression_amount_micros, reservation_expires_at, occurred_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;
    private static final String FIND_EVENT = """
            SELECT kind, reservation_id, lease_id, campaign_id, impression_amount_micros,
                   reservation_expires_at, occurred_at
              FROM monetary_event WHERE event_id = ?
            """;
    private static final String INSERT_OUTCOME = """
            INSERT INTO reservation_monetary_outcome (
                reservation_id, event_id, kind, lease_id, campaign_id,
                impression_amount_micros, reservation_expires_at, occurred_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (reservation_id) DO NOTHING
            """;
    private static final String FIND_OUTCOME = """
            SELECT event_id, kind, lease_id, campaign_id, impression_amount_micros,
                   reservation_expires_at, occurred_at
              FROM reservation_monetary_outcome WHERE reservation_id = ?
            """;
    private static final String INSERT_CONFLICT = """
            INSERT INTO monetary_event_conflict (
                reservation_id, lease_id, existing_event_id, incoming_event_id,
                existing_kind, incoming_kind
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (existing_event_id, incoming_event_id) DO NOTHING
            """;

    private final DataSource dataSource;
    private final Executor jdbcExecutor;

    public PostgreSqlReservationOutcomeStore(DataSource dataSource, Executor jdbcExecutor) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.jdbcExecutor = Objects.requireNonNull(jdbcExecutor, "jdbcExecutor");
    }

    @Override
    public CompletionStage<OutcomeDecision> decide(MonetaryNoticeEvent candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return CompletableFuture.supplyAsync(() -> decideBlocking(candidate), jdbcExecutor);
    }

    private OutcomeDecision decideBlocking(MonetaryNoticeEvent event) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int inserted = insertEvent(connection, event);
                if (inserted == 0) {
                    StoredEvent existing = findEvent(connection, event.eventId());
                    if (existing == null || !existing.matches(event)) {
                        recordConflict(connection, event, existing);
                        connection.commit();
                        return new OutcomeConflict(existing.toEvent(), event.kind());
                    }
                }

                int outcomeInserted = insertOutcome(connection, event);
                StoredOutcome outcome = findOutcome(connection, event.reservationId());
                if (outcome.matches(event)) {
                    connection.commit();
                    return new OutcomeChosen(
                            outcome.toEvent(event.reservationId()),
                            outcomeInserted == 1
                    );
                }
                recordConflict(connection, event, outcome.asEvent());
                connection.commit();
                return new OutcomeConflict(
                        outcome.toEvent(event.reservationId()),
                        event.kind()
                );
            } catch (SQLException | RuntimeException failure) {
                rollbackQuietly(connection);
                throw new MoneyEventStoreException("failed to append monetary event", failure);
            }
        } catch (SQLException failure) {
            throw new MoneyEventStoreException("failed to access monetary event store", failure);
        }
    }

    private int insertEvent(Connection connection, MonetaryNoticeEvent event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_EVENT)) {
            bindEvent(statement, event, 1);
            return statement.executeUpdate();
        }
    }

    private int insertOutcome(Connection connection, MonetaryNoticeEvent event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_OUTCOME)) {
            statement.setString(1, event.reservationId());
            statement.setString(2, event.eventId());
            statement.setString(3, event.kind().name());
            statement.setObject(4, UUID.fromString(event.leaseId()));
            statement.setString(5, event.campaignId());
            statement.setLong(6, event.impressionAmountMicros());
            statement.setTimestamp(7, Timestamp.from(event.reservationExpiresAt()));
            statement.setTimestamp(8, Timestamp.from(event.receivedAt()));
            return statement.executeUpdate();
        }
    }

    private StoredEvent findEvent(Connection connection, String eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_EVENT)) {
            statement.setString(1, eventId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? storedEvent(row, eventId) : null;
            }
        }
    }

    private StoredOutcome findOutcome(Connection connection, String reservationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_OUTCOME)) {
            statement.setString(1, reservationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("monetary outcome was not recorded");
                }
                return new StoredOutcome(
                        row.getString("event_id"),
                        MonetaryEventKind.valueOf(row.getString("kind")),
                        row.getObject("lease_id", UUID.class).toString(),
                        row.getString("campaign_id"),
                        row.getLong("impression_amount_micros"),
                        row.getTimestamp("reservation_expires_at").toInstant(),
                        row.getTimestamp("occurred_at").toInstant()
                );
            }
        }
    }

    private void recordConflict(
            Connection connection,
            MonetaryNoticeEvent incoming,
            StoredEvent existing
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_CONFLICT)) {
            statement.setString(1, incoming.reservationId());
            statement.setObject(2, UUID.fromString(incoming.leaseId()));
            statement.setString(3, existing == null ? incoming.eventId() : existing.eventId());
            statement.setString(4, incoming.eventId());
            statement.setString(5, existingKind(existing, incoming).name());
            statement.setString(6, incoming.kind().name());
            statement.executeUpdate();
        }
    }

    private static void bindEvent(
            PreparedStatement statement,
            MonetaryNoticeEvent event,
            int offset
    ) throws SQLException {
        statement.setString(offset, event.eventId());
        statement.setString(offset + 1, event.kind().name());
        statement.setString(offset + 2, event.reservationId());
        statement.setObject(offset + 3, UUID.fromString(event.leaseId()));
        statement.setString(offset + 4, event.campaignId());
        statement.setLong(offset + 5, event.impressionAmountMicros());
        statement.setTimestamp(offset + 6, Timestamp.from(event.reservationExpiresAt()));
        statement.setTimestamp(offset + 7, Timestamp.from(event.receivedAt()));
    }

    private static StoredEvent storedEvent(ResultSet row, String eventId) throws SQLException {
        return new StoredEvent(
                eventId,
                MonetaryEventKind.valueOf(row.getString("kind")),
                row.getString("reservation_id"),
                row.getObject("lease_id", UUID.class).toString(),
                row.getString("campaign_id"),
                row.getLong("impression_amount_micros"),
                row.getTimestamp("reservation_expires_at").toInstant(),
                row.getTimestamp("occurred_at").toInstant()
        );
    }

    private static MonetaryEventKind existingKind(
            StoredEvent existing,
            MonetaryNoticeEvent incoming
    ) {
        return existing == null ? incoming.kind() : existing.kind();
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 원래 실패를 보존한다.
        }
    }

    private record StoredEvent(
            String eventId,
            MonetaryEventKind kind,
            String reservationId,
            String leaseId,
            String campaignId,
            long amountMicros,
            java.time.Instant expiresAt,
            java.time.Instant occurredAt
    ) {
        boolean matches(MonetaryNoticeEvent event) {
            return kind == event.kind()
                    && reservationId.equals(event.reservationId())
                    && leaseId.equals(event.leaseId())
                    && campaignId.equals(event.campaignId())
                    && amountMicros == event.impressionAmountMicros()
                    && expiresAt.equals(event.reservationExpiresAt());
        }

        MonetaryNoticeEvent toEvent() {
            return new MonetaryNoticeEvent(
                    eventId, kind, reservationId, leaseId, campaignId,
                    amountMicros, expiresAt, occurredAt
            );
        }
    }

    private record StoredOutcome(
            String eventId,
            MonetaryEventKind kind,
            String leaseId,
            String campaignId,
            long amountMicros,
            java.time.Instant expiresAt,
            java.time.Instant occurredAt
    ) {
        boolean matches(MonetaryNoticeEvent event) {
            return kind == event.kind()
                    && leaseId.equals(event.leaseId())
                    && campaignId.equals(event.campaignId())
                    && amountMicros == event.impressionAmountMicros()
                    && expiresAt.equals(event.reservationExpiresAt());
        }

        StoredEvent asEvent() {
            return new StoredEvent(
                    eventId, kind, "", leaseId, campaignId, amountMicros, expiresAt, occurredAt
            );
        }

        MonetaryNoticeEvent toEvent(String reservationId) {
            return new MonetaryNoticeEvent(
                    eventId, kind, reservationId, leaseId, campaignId,
                    amountMicros, expiresAt, occurredAt
            );
        }
    }

    private static final class MoneyEventStoreException extends RuntimeException {
        private MoneyEventStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
