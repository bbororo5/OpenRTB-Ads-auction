package com.bbororo.rtb.dsp;

import com.bbororo.rtb.dsp.DspOperationalSettings.JdbcStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** 리전 예산 원장과 금액 사건 저장소의 독립 커넥션 풀을 소유한다. */
final class DspDataSources implements AutoCloseable {

    private final HikariDataSource ledger;
    private final HikariDataSource outcome;

    private DspDataSources(HikariDataSource ledger, HikariDataSource outcome) {
        this.ledger = ledger;
        this.outcome = outcome;
    }

    static DspDataSources open(DspOperationalSettings settings) {
        HikariDataSource ledger = dataSource(settings.ledgerStore(), "dsp-ledger");
        try {
            return new DspDataSources(
                    ledger,
                    dataSource(settings.outcomeStore(), "dsp-outcome")
            );
        } catch (RuntimeException failure) {
            ledger.close();
            throw failure;
        }
    }

    HikariDataSource ledger() {
        return ledger;
    }

    HikariDataSource outcome() {
        return outcome;
    }

    private static HikariDataSource dataSource(JdbcStore settings, String poolName) {
        var config = new HikariConfig();
        config.setPoolName(poolName);
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.maximumPoolSize());
        config.setMinimumIdle(0);
        config.setInitializationFailTimeout(10_000);
        config.setConnectionTimeout(5_000);
        return new HikariDataSource(config);
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            outcome.close();
        } catch (RuntimeException exception) {
            failure = exception;
        }
        try {
            ledger.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
