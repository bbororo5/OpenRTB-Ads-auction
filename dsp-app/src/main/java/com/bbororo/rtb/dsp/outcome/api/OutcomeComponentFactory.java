package com.bbororo.rtb.dsp.outcome.api;

import com.bbororo.rtb.dsp.outcome.internal.DefaultReservationOutcomeProcessor;
import com.bbororo.rtb.dsp.outcome.internal.PostgreSqlLeaseOutcomeView;
import com.bbororo.rtb.dsp.outcome.internal.PostgreSqlReservationOutcomeStore;
import com.bbororo.rtb.dsp.outcome.internal.ReservationExpirationService;
import com.bbororo.rtb.dsp.outcome.internal.ReservationExpirationWorker;
import com.bbororo.rtb.dsp.proof.api.ReservationNoticeVerifier;
import com.bbororo.rtb.dsp.spending.api.ReservationExpirationSource;
import com.bbororo.rtb.dsp.spending.api.ReservationFinalizer;
import com.bbororo.rtb.dsp.spending.api.ReservationStateView;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javax.sql.DataSource;

/** Outcome PostgreSQL 포트와 예약 만료 작업자를 하나의 실행 컴포넌트로 조립한다. */
public final class OutcomeComponentFactory {

    private OutcomeComponentFactory() {
    }

    public static Runtime create(
            DataSource dataSource,
            Executor jdbcExecutor,
            ReservationNoticeVerifier verifier,
            ReservationFinalizer localBudget,
            ReservationStateView localState,
            ReservationExpirationSource expirations,
            Duration expirationRetryDelay,
            Consumer<Throwable> failureHandler
    ) {
        verifySchema(dataSource);
        var store = new PostgreSqlReservationOutcomeStore(dataSource, jdbcExecutor);
        var outcomeView = new PostgreSqlLeaseOutcomeView(dataSource, jdbcExecutor);
        var processor = new DefaultReservationOutcomeProcessor(verifier, store, localBudget);
        var expirationService = new ReservationExpirationService(store, localBudget, localState);
        var expirationWorker = new ReservationExpirationWorker(
                expirations,
                expirationService,
                expirationRetryDelay,
                failureHandler
        );
        return new Runtime(processor, outcomeView, expirationWorker);
    }

    private static void verifySchema(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection()) {
            verifyTable(connection, "monetary_event");
            verifyTable(connection, "reservation_monetary_outcome");
            verifyTable(connection, "monetary_event_conflict");
        } catch (SQLException failure) {
            throw new IllegalStateException("DSP outcome store schema is not ready", failure);
        }
    }

    private static void verifyTable(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM " + table + " WHERE FALSE")) {
            statement.executeQuery();
        }
    }

    public static final class Runtime implements AutoCloseable {

        private final ReservationOutcomeProcessor processor;
        private final LeaseOutcomeView leaseOutcomeView;
        private final ReservationExpirationWorker expirationWorker;
        private boolean started;
        private boolean closed;

        private Runtime(
                ReservationOutcomeProcessor processor,
                LeaseOutcomeView leaseOutcomeView,
                ReservationExpirationWorker expirationWorker
        ) {
            this.processor = processor;
            this.leaseOutcomeView = leaseOutcomeView;
            this.expirationWorker = expirationWorker;
        }

        public ReservationOutcomeProcessor processor() {
            return processor;
        }

        public LeaseOutcomeView leaseOutcomeView() {
            return leaseOutcomeView;
        }

        public synchronized void start() {
            if (closed || started) {
                throw new IllegalStateException("Outcome runtime cannot be started");
            }
            expirationWorker.start();
            started = true;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            expirationWorker.close();
        }
    }
}
