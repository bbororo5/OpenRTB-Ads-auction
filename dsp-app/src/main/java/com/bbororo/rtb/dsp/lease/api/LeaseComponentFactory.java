package com.bbororo.rtb.dsp.lease.api;

import com.bbororo.rtb.dsp.lease.internal.AdaptiveLeaseDemandPolicy;
import com.bbororo.rtb.dsp.lease.internal.LeaseMaintenanceScheduler;
import com.bbororo.rtb.dsp.lease.internal.LeaseMaintenanceWorker;
import com.bbororo.rtb.dsp.lease.internal.LeaseRefillService;
import com.bbororo.rtb.dsp.lease.internal.LeaseSettlementService;
import com.bbororo.rtb.dsp.lease.internal.PostgreSqlRegionalBudgetLedger;
import com.bbororo.rtb.dsp.outcome.api.LeaseOutcomeView;
import com.bbororo.rtb.dsp.spending.api.LeaseInstaller;
import com.bbororo.rtb.dsp.spending.api.LocalLeaseSupplyView;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javax.sql.DataSource;

/** 리전 원장·로컬 설치·Outcome 집계를 보충과 정산 작업자로 조립한다. */
public final class LeaseComponentFactory {

    private LeaseComponentFactory() {
    }

    public static Runtime create(
            DataSource ledgerDataSource,
            Executor jdbcExecutor,
            Settings settings,
            String instanceId,
            LocalLeaseSupplyView supplyView,
            LeaseInstaller leaseInstaller,
            LeaseOutcomeView outcomeView,
            int activeCampaignCount,
            Consumer<Throwable> failureHandler
    ) {
        verifySchema(ledgerDataSource);
        var ledger = new PostgreSqlRegionalBudgetLedger(
                ledgerDataSource,
                jdbcExecutor,
                new PostgreSqlRegionalBudgetLedger.Settings(
                        settings.leaseDuration(),
                        settings.pacingCoverage(),
                        settings.maximumReservationLifetime(),
                        settings.eventVisibilityMargin(),
                        settings.minimumLeaseMicros(),
                        settings.maximumLeaseMicros()
                )
        );
        var worker = new LeaseMaintenanceWorker(
                instanceId,
                instanceId + "-lease-settlement",
                supplyView,
                new AdaptiveLeaseDemandPolicy(
                        settings.demandCoverage(),
                        settings.minimumLeaseMicros(),
                        settings.maximumLeaseMicros()
                ),
                new LeaseRefillService(ledger, leaseInstaller),
                new LeaseSettlementService(ledger, outcomeView),
                ledger,
                settings.settlementBatchSize(),
                settings.settlementClaimDuration()
        );
        var scheduler = new LeaseMaintenanceScheduler(
                worker, settings.maintenanceInterval(), failureHandler);
        return new Runtime(worker, scheduler, activeCampaignCount);
    }

    private static void verifySchema(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection()) {
            verifyTable(connection, "regional_campaign_budget");
            verifyTable(connection, "budget_lease");
        } catch (SQLException failure) {
            throw new IllegalStateException("DSP regional ledger schema is not ready", failure);
        }
    }

    private static void verifyTable(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM " + table + " WHERE FALSE")) {
            statement.executeQuery();
        }
    }

    public record Settings(
            Duration leaseDuration,
            Duration pacingCoverage,
            Duration maximumReservationLifetime,
            Duration eventVisibilityMargin,
            long minimumLeaseMicros,
            long maximumLeaseMicros,
            Duration maintenanceInterval,
            Duration demandCoverage,
            int settlementBatchSize,
            Duration settlementClaimDuration
    ) {
        public Settings {
            requirePositive(leaseDuration, "leaseDuration");
            requirePositive(pacingCoverage, "pacingCoverage");
            requirePositive(maximumReservationLifetime, "maximumReservationLifetime");
            requirePositive(eventVisibilityMargin, "eventVisibilityMargin");
            if (minimumLeaseMicros <= 0L || maximumLeaseMicros < minimumLeaseMicros) {
                throw new IllegalArgumentException("lease bounds are invalid");
            }
            requirePositive(maintenanceInterval, "maintenanceInterval");
            requirePositive(demandCoverage, "demandCoverage");
            if (settlementBatchSize <= 0) {
                throw new IllegalArgumentException("settlementBatchSize must be positive");
            }
            requirePositive(settlementClaimDuration, "settlementClaimDuration");
        }

        private static void requirePositive(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }

    public static final class Runtime implements AutoCloseable {

        private final LeaseMaintenanceWorker worker;
        private final LeaseMaintenanceScheduler scheduler;
        private final int activeCampaignCount;
        private boolean started;
        private boolean closed;

        private Runtime(
                LeaseMaintenanceWorker worker,
                LeaseMaintenanceScheduler scheduler,
                int activeCampaignCount
        ) {
            this.worker = worker;
            this.scheduler = scheduler;
            if (activeCampaignCount < 0) {
                throw new IllegalArgumentException("activeCampaignCount must not be negative");
            }
            this.activeCampaignCount = activeCampaignCount;
        }

        public synchronized void start() {
            if (closed || started) {
                throw new IllegalStateException("Lease runtime cannot be started");
            }
            LeaseMaintenanceWorker.MaintenanceReport initial;
            try {
                initial = worker.runOnce().toCompletableFuture().join();
            } catch (CompletionException failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                throw new IllegalStateException("initial lease maintenance failed", cause);
            }
            if (initial.refillsSucceeded() < activeCampaignCount) {
                throw new IllegalStateException(
                        "initial lease supply is incomplete: "
                                + initial.refillsSucceeded() + '/' + activeCampaignCount);
            }
            scheduler.start();
            started = true;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            scheduler.close();
        }
    }
}
