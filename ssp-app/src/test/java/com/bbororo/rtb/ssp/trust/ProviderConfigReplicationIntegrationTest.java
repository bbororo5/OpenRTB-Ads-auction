package com.bbororo.rtb.ssp.trust;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 로컬 Compose publisher→subscriber 논리 복제 위에서 실제 JDBC 읽기를 검증한다. */
@Tag("provider-config-replication")
class ProviderConfigReplicationIntegrationTest {

    @Test
    void loadsTheReplicatedActiveVersionFromItsInjectedDatabase() throws Exception {
        try (var publisher = RegionalDataSourceFactory.create(settings("publisher"));
             var subscriber = RegionalDataSourceFactory.create(settings("subscriber"))) {
            long firstVersion = nextVersion(publisher);
            publishVersion(publisher, firstVersion, true);

            ProviderTrustSnapshot enabled = waitForSnapshot(subscriber, firstVersion);
            assertTrue(enabled.permits("provider-integration", "key-integration"));

            long secondVersion = firstVersion + 1;
            publishVersion(publisher, secondVersion, false);

            ProviderTrustSnapshot disabled = waitForSnapshot(subscriber, secondVersion);
            assertFalse(disabled.permits("provider-integration", "key-integration"));
        }
    }

    private static RegionalDataSourceFactory.DatabaseConnectionSettings settings(String role) {
        return new RegionalDataSourceFactory.DatabaseConnectionSettings(
                System.getProperty("provider.config.%s.jdbc-url".formatted(role)),
                System.getProperty("provider.config.username"),
                System.getProperty("provider.config.password")
        );
    }

    private static void publishVersion(
            javax.sql.DataSource publisher,
            long version,
            boolean active
    ) throws SQLException {
        try (Connection connection = publisher.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertVersion(connection, version);
                insertPolicy(connection, version, active);
                insertKey(connection, version, active);
                updateHead(connection, version);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static long nextVersion(javax.sql.DataSource publisher) throws SQLException {
        try (Connection connection = publisher.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COALESCE(MAX(version), 0) + 1 AS next_version
                     FROM provider_config_version
                     """ );
             var result = statement.executeQuery()) {
            result.next();
            return result.getLong("next_version");
        }
    }

    private static void insertVersion(Connection connection, long version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO provider_config_version (version, checksum, published_at)
                VALUES (?, ?, clock_timestamp())
                """)) {
            statement.setLong(1, version);
            statement.setString(2, "integration-version-%d".formatted(version));
            statement.executeUpdate();
        }
    }

    private static void insertPolicy(Connection connection, long version, boolean active) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO provider_policy (version, provider_id, active)
                VALUES (?, 'provider-integration', ?)
                """)) {
            statement.setLong(1, version);
            statement.setBoolean(2, active);
            statement.executeUpdate();
        }
    }

    private static void insertKey(Connection connection, long version, boolean active) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO provider_key (version, provider_id, key_id, active)
                VALUES (?, 'provider-integration', 'key-integration', ?)
                """)) {
            statement.setLong(1, version);
            statement.setBoolean(2, active);
            statement.executeUpdate();
        }
    }

    private static void updateHead(Connection connection, long version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO provider_config_head (scope, active_version)
                VALUES ('global', ?)
                ON CONFLICT (scope) DO UPDATE SET active_version = EXCLUDED.active_version
                """)) {
            statement.setLong(1, version);
            statement.executeUpdate();
        }
    }

    private static ProviderTrustSnapshot waitForSnapshot(javax.sql.DataSource subscriber, long version)
            throws InterruptedException {
        PostgreSqlProviderConfigReader reader = new PostgreSqlProviderConfigReader(subscriber);
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));

        while (Instant.now().isBefore(deadline)) {
            try {
                ProviderTrustSnapshot snapshot = reader.loadActiveSnapshot();
                if (snapshot.version() == version) {
                    return snapshot;
                }
            } catch (IllegalStateException ignored) {
                // Subscription 초기 복사와 첫 head 발행 사이에는 설정이 없을 수 있다.
            }
            Thread.sleep(100);
        }
        throw new AssertionError("subscriber가 설정 버전 %d을 받지 못했습니다.".formatted(version));
    }
}
